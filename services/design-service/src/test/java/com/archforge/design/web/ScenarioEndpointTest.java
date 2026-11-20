package com.archforge.design.web;

import com.archforge.contracts.lifecycle.ScenarioType;
import com.archforge.design.canvas.CanvasRepository;
import com.archforge.design.domain.DesignEntity;
import com.archforge.design.repository.DesignRepository;
import com.archforge.design.service.SubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scenario catalog is a literal route. It must not be parsed as a design id,
 * and an unknown workload must be refused before a design or a run is stored.
 */
class ScenarioEndpointTest {

    private static final UsernamePasswordAuthenticationToken AUTH =
            new UsernamePasswordAuthenticationToken("scenario-owner", "unused");

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private DesignRepository designs;
    private SubmissionService submissions;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        designs = mock(DesignRepository.class);
        submissions = mock(SubmissionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DesignController(
                        designs, mock(CanvasRepository.class), submissions, mapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void catalogIsServedAheadOfTheDesignIdRoute() throws Exception {
        mvc.perform(get("/api/v1/designs/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(ScenarioType.allValues().size()))
                .andExpect(jsonPath("$[0].value").value(ScenarioType.allValues().get(0)))
                .andExpect(jsonPath("$[?(@.value == 'flash_sale')].displayName").isNotEmpty())
                .andExpect(jsonPath("$[?(@.value == 'flash_sale')].description").isNotEmpty());
    }

    @Test
    void createRejectsAnUnknownScenarioBeforeSaving() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/designs").principal(AUTH)
                        .contentType("application/json")
                        .content("{\"name\":\"Orders\",\"scenario\":\"burst\",\"topology\":{\"nodes\":[],\"edges\":[]}}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResolvedException())
                .isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) result.getResolvedException()).getReason())
                .contains("burst")
                .contains("flash_sale");
        verify(designs, never()).save(any(DesignEntity.class));
    }

    @Test
    void submitRejectsAnUnknownScenarioBeforeQueueing() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/designs/{id}/submissions", UUID.randomUUID())
                        .principal(AUTH)
                        .contentType("application/json")
                        .content("{\"scenario\":\"burst\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResolvedException())
                .isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) result.getResolvedException()).getReason())
                .contains("burst");
        verify(submissions, never()).submit(any(), any(), any(), any(), any());
    }
}
