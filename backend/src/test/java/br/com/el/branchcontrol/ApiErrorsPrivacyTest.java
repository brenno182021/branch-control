package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class ApiErrorsPrivacyTest {
    static final String PASSWORD="privacy-password-sentinel";
    static final String HASH="privacy-bcrypt-hash-sentinel";
    @RestController static class FailureController {
        @GetMapping("/failure") public String fail() {
            throw new DataIntegrityViolationException("SQL params: "+PASSWORD+" "+HASH,
                new SQLException("Failing row contains "+PASSWORD+" "+HASH,"23514"));
        }
    }
    @Test void databaseFailureDoesNotLogOrReturnParametersMessageCauseOrStack(CapturedOutput output) throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new FailureController()).setControllerAdvice(new ApiErrors()).build();
        String response=mvc.perform(get("/failure")).andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(PASSWORD)); assertFalse(response.contains(HASH));
        assertFalse(output.getAll().contains(PASSWORD)); assertFalse(output.getAll().contains(HASH));
        assertFalse(output.getAll().contains("FailureController.fail"));
        assertTrue(output.getAll().contains("DataIntegrityViolationException"));
        assertTrue(output.getAll().contains("23514"));
    }
}
