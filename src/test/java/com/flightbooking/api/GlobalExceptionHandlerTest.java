package com.flightbooking.api;

import com.flightbooking.exception.InvalidBookingStateException;
import com.flightbooking.exception.PaymentFailedException;
import com.flightbooking.exception.ResourceNotFoundException;
import com.flightbooking.exception.SeatUnavailableException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc — no Spring context, just the advice bound to a
 * dummy controller that raises one exception per endpoint. Covers
 * every branch of GlobalExceptionHandler so an accidental removal of
 * a mapping (which would silently 500 in prod) fails here.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void resourceNotFound_mapsTo404() throws Exception {
        mvc.perform(get("/t/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("gone"))
                .andExpect(jsonPath("$.path").value("/t/not-found"));
    }

    @Test
    void seatUnavailable_mapsTo409() throws Exception {
        mvc.perform(get("/t/seat-taken"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("taken"));
    }

    @Test
    void invalidBookingState_mapsTo409() throws Exception {
        mvc.perform(get("/t/bad-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("bad state"));
    }

    @Test
    void paymentFailed_mapsTo402() throws Exception {
        mvc.perform(get("/t/pay-failed"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.message").value("gateway"));
    }

    @Test
    void dataIntegrityViolation_genericConflictMessage() throws Exception {
        mvc.perform(get("/t/dup"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Conflicting write (seat taken or duplicate request)"));
    }

    @Test
    void beanValidationFailure_mapsTo400_withFieldNamesInMessage() throws Exception {
        mvc.perform(post("/t/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    void missingRequiredHeader_mapsTo400() throws Exception {
        mvc.perform(get("/t/needs-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("X-User-Id")));
    }

    @Test
    void headerTypeMismatch_mapsTo400() throws Exception {
        mvc.perform(get("/t/needs-header").header("X-User-Id", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("X-User-Id")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Long")));
    }

    @Test
    void illegalArgument_mapsTo400() throws Exception {
        mvc.perform(get("/t/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("bad input"));
    }

    @Test
    void unexpectedException_mapsTo500_asLastResort() throws Exception {
        mvc.perform(get("/t/oops"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("boom"));
    }

    // ---- test fixtures ------------------------------------------------

    public record Body(@NotBlank String name) {}

    @RestController
    static class ThrowingController {
        @GetMapping("/t/not-found")
        public String notFound() { throw new ResourceNotFoundException("gone"); }

        @GetMapping("/t/seat-taken")
        public String seatTaken() { throw new SeatUnavailableException("taken"); }

        @GetMapping("/t/bad-state")
        public String badState() { throw new InvalidBookingStateException("bad state"); }

        @GetMapping("/t/pay-failed")
        public String payFailed() { throw new PaymentFailedException("gateway"); }

        @GetMapping("/t/dup")
        public String dup() { throw new DataIntegrityViolationException("duplicate key"); }

        @PostMapping("/t/validated")
        public String validated(@RequestBody @Valid Body body) { return "ok"; }

        @GetMapping("/t/needs-header")
        public String needsHeader(@RequestHeader("X-User-Id") Long userId) { return "ok"; }

        @GetMapping("/t/illegal")
        public String illegal() { throw new IllegalArgumentException("bad input"); }

        @GetMapping("/t/oops")
        public String oops() { throw new RuntimeException("boom"); }

        // Consumes a query param — the framework only surfaces MissingServletRequestParameterException
        // for @RequestParam. We could cover it here, but a mapping isn't wired in the advice yet
        // (identified as gap A9 in the audit), so keep it out to avoid asserting a not-yet-implemented behaviour.
        @GetMapping("/t/needs-param")
        public String needsParam(@RequestParam String q) { return q; }
    }
}
