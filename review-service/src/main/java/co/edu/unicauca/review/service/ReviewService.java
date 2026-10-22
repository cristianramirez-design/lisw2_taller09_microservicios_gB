package co.edu.unicauca.review.service;

import co.edu.unicauca.review.client.*;
import co.edu.unicauca.review.dto.*;
import co.edu.unicauca.review.exception.*;
import co.edu.unicauca.review.messaging.ReviewEventPublisher;
import co.edu.unicauca.review.messaging.messages.QuestionReviewed;
import co.edu.unicauca.review.messaging.messages.ReviewerAssigned;
import co.edu.unicauca.review.model.*;
import co.edu.unicauca.review.repository.ReviewAssignmentRepository;
import co.edu.unicauca.review.repository.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Punto 7 del escenario. La asignación se coordina como una saga corta con
 * compensación:
 *   1. verificar elegibilidad (pregunta en PENDING_REVIEW, revisor con rol REVIEWER y distinto del autor);
 *   2. registrar la asignación en PENDING;
 *   3. pedir al Lifecycle Service la transición PENDING_REVIEW -> IN_REVIEW;
 *   4a. éxito  -> asignación ASSIGNED y se publica ReviewerAssigned (asíncrono);
 *   4b. fallo  -> asignación CANCELLED (compensación) y se informa el error.
 * Así nunca queda una asignación activa sobre una pregunta que no está IN_REVIEW.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final Set<AssignmentStatus> ACTIVE = Set.of(AssignmentStatus.PENDING, AssignmentStatus.ASSIGNED);

    private final ReviewAssignmentRepository assignmentRepository;
    private final ReviewRepository reviewRepository;
    private final UserClient userClient;
    private final QuestionClient questionClient;
    private final LifecycleClient lifecycleClient;
    private final ReviewEventPublisher publisher;

    public ReviewService(ReviewAssignmentRepository assignmentRepository, ReviewRepository reviewRepository,
                         UserClient userClient, QuestionClient questionClient, LifecycleClient lifecycleClient,
                         ReviewEventPublisher publisher) {
        this.assignmentRepository = assignmentRepository;
        this.reviewRepository = reviewRepository;
        this.userClient = userClient;
        this.questionClient = questionClient;
        this.lifecycleClient = lifecycleClient;
        this.publisher = publisher;
    }

    public AssignmentDTO assignReviewer(String authorization, AssignReviewerRequest request) {
        AuthenticatedUser admin = userClient.authenticate(authorization);
        if (!admin.hasRole("ADMIN")) {
            throw new ForbiddenException("Solo un administrador puede asignar revisores");
        }

        // 1. Elegibilidad de la pregunta
        LifecycleState state = lifecycleClient.getState(request.questionId());
        if (!"PENDING_REVIEW".equals(state.state())) {
            throw new ConflictException("La pregunta " + request.questionId()
                    + " no es elegible: debe estar en PENDING_REVIEW (estado actual: " + state.state() + ")");
        }
        if (assignmentRepository.existsByQuestionIdAndStatusIn(request.questionId(), ACTIVE)) {
            throw new ConflictException("La pregunta " + request.questionId() + " ya tiene un revisor asignado");
        }
        // 1b. Elegibilidad del revisor
        AuthenticatedUser reviewer;
        try {
            reviewer = userClient.findById(request.reviewerId());
        } catch (ApiException ex) {
            if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                throw new UnprocessableException("El revisor " + request.reviewerId() + " no existe");
            }
            throw ex;
        }
        if (!reviewer.hasRole("REVIEWER")) {
            throw new UnprocessableException("El usuario " + reviewer.username() + " no tiene el rol REVIEWER");
        }
        QuestionInfo question = questionClient.findById(request.questionId());
        if (reviewer.id().equals(question.authorId())) {
            throw new UnprocessableException("El autor de la pregunta no puede ser su revisor (revisión por pares)");
        }

        // 2. Registro local en PENDING
        ReviewAssignment assignment = assignmentRepository.save(
                new ReviewAssignment(request.questionId(), reviewer.id(), admin.id()));

        // 3. Transición solicitada a la autoridad del estado (+ compensación si falla)
        LifecycleState newState;
        try {
            newState = lifecycleClient.requestTransition(request.questionId(), "IN_REVIEW", admin.username(),
                    "Revisor asignado: " + reviewer.username());
        } catch (ApiException ex) {
            assignment.updateStatus(AssignmentStatus.CANCELLED, "Compensada: " + ex.getMessage());
            assignmentRepository.save(assignment);
            log.warn("[ReviewService] Asignación {} cancelada: {}", assignment.getId(), ex.getMessage());
            throw ex;
        }
        assignment.updateStatus(AssignmentStatus.ASSIGNED, "Pregunta en IN_REVIEW");
        assignmentRepository.save(assignment);

        // 4. Notificación asíncrona: no bloquea ni revierte la asignación
        boolean queued = publisher.publishReviewerAssigned(new ReviewerAssigned(assignment.getId(),
                request.questionId(), reviewer.id(), admin.id(), LocalDateTime.now()));
        log.info("[ReviewService] Revisor {} asignado a la pregunta {}", reviewer.username(), request.questionId());
        return AssignmentDTO.from(assignment, newState.state(), queued);
    }

    /** Dictamen del revisor: APPROVED o REJECTED (con motivo obligatorio). */
    public ReviewDTO decide(String authorization, Long assignmentId, ReviewDecisionRequest request) {
        AuthenticatedUser user = userClient.authenticate(authorization);
        ReviewAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new NotFoundException("No existe la asignación " + assignmentId));
        if (!assignment.getReviewerId().equals(user.id())) {
            throw new ForbiddenException("Solo el revisor asignado puede emitir el dictamen");
        }
        if (assignment.getStatus() != AssignmentStatus.ASSIGNED) {
            throw new ConflictException("La asignación está en estado " + assignment.getStatus()
                    + "; solo se puede dictaminar una asignación ASSIGNED");
        }
        ReviewResult result = parseResult(request.result());
        if (result == ReviewResult.REJECTED && (request.comments() == null || request.comments().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Para rechazar la pregunta debe indicar el motivo (comments)");
        }

        LifecycleState newState = lifecycleClient.requestTransition(assignment.getQuestionId(), result.name(),
                user.username(), request.comments());

        Review review = reviewRepository.save(new Review(assignment.getId(), assignment.getQuestionId(), user.id(),
                result, request.comments()));
        assignment.updateStatus(AssignmentStatus.COMPLETED, "Dictamen: " + result);
        assignmentRepository.save(assignment);

        Long authorId = null;
        try {
            authorId = questionClient.findById(assignment.getQuestionId()).authorId();
        } catch (ApiException ex) {
            log.warn("[ReviewService] No se pudo obtener el autor para notificar: {}", ex.getMessage());
        }
        publisher.publishQuestionReviewed(new QuestionReviewed(review.getId(), assignment.getQuestionId(), authorId,
                user.id(), result.name(), request.comments(), LocalDateTime.now()));
        return ReviewDTO.from(review, newState.state());
    }

    public List<AssignmentDTO> assignments(Long questionId) {
        List<ReviewAssignment> list = questionId == null ? assignmentRepository.findAll()
                : assignmentRepository.findByQuestionId(questionId);
        return list.stream().map(AssignmentDTO::from).toList();
    }

    public AssignmentDTO assignment(Long id) {
        return assignmentRepository.findById(id).map(AssignmentDTO::from)
                .orElseThrow(() -> new NotFoundException("No existe la asignación " + id));
    }

    public List<ReviewDTO> reviews(Long questionId) {
        return (questionId == null ? reviewRepository.findAll() : reviewRepository.findByQuestionId(questionId))
                .stream().map(r -> ReviewDTO.from(r, null)).toList();
    }

    private static ReviewResult parseResult(String value) {
        try {
            return ReviewResult.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "result debe ser APPROVED o REJECTED");
        }
    }
}
