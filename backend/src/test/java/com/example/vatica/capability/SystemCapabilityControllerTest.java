package com.example.vatica.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

class SystemCapabilityControllerTest {

    @Test
    void exposesStableCapabilitySnapshotShape() throws Exception {
        SystemCapabilityService service = mock(SystemCapabilityService.class);
        when(service.snapshot(any())).thenReturn(new SystemCapabilityService.Snapshot(List.of(
                new SystemCapabilityService.CapabilityView("model", "模型", SystemCapabilityService.Status.READY,
                        "模型可用。", null))));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SystemCapabilityController(service)).build();

        String body = RequestIdentityContext.callWith(new RequestIdentity(7L, 9L, "USER", "alice"), () -> {
            try {
                return mvc.perform(get("/api/system/capabilities")).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(body).contains("\"id\":\"model\"").contains("\"status\":\"READY\"")
                .doesNotContain("apiKey");
    }
}
