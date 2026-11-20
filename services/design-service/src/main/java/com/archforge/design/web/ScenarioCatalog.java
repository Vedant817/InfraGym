package com.archforge.design.web;

import com.archforge.contracts.lifecycle.ScenarioType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The workloads a design is allowed to name.
 *
 * <p>An unrecognised scenario used to be stored as {@code normal}. The run then
 * executed steady state while the form still showed whatever the user had typed,
 * so the result and the request described different experiments. Blank still
 * means steady state, because that is what {@link ScenarioType#fromValue} does
 * for an omitted field. Anything else must be one of the declared workloads.
 */
public final class ScenarioCatalog {

    private ScenarioCatalog() {
    }

    public static List<Dtos.ScenarioOption> list() {
        return ScenarioType.all().stream()
                .map(scenario -> new Dtos.ScenarioOption(
                        scenario.getValue(), scenario.getDisplayName(), scenario.getDescription()))
                .toList();
    }

    public static ScenarioType require(String raw) {
        try {
            return ScenarioType.fromValue(raw);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
}
