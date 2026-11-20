package com.archforge.design.web;

import com.archforge.contracts.lifecycle.ScenarioType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioCatalogTest {

    @Test
    void listsEveryDeclaredWorkloadInDeclarationOrder() {
        assertThat(ScenarioCatalog.list())
                .extracting(Dtos.ScenarioOption::value)
                .containsExactlyElementsOf(ScenarioType.allValues());
        assertThat(ScenarioCatalog.list())
                .allSatisfy(option -> {
                    assertThat(option.displayName()).isNotBlank();
                    assertThat(option.description()).isNotBlank();
                });
    }

    @Test
    void blankAndAliasesResolveToTheDeclaredValue() {
        assertThat(ScenarioCatalog.require(null).getValue()).isEqualTo("normal");
        assertThat(ScenarioCatalog.require("  ").getValue()).isEqualTo("normal");
        assertThat(ScenarioCatalog.require("FLASH-SALE").getValue()).isEqualTo("flash_sale");
    }

    @Test
    void anUnknownScenarioIsRejectedAndNamesTheValidWorkloads() {
        assertThatThrownBy(() -> ScenarioCatalog.require("burst"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).contains("burst").contains("flash_sale");
                });
    }
}
