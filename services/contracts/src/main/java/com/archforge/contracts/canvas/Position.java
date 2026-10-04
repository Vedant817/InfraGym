package com.archforge.contracts.canvas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/** Canvas coordinates in flow space. Purely presentational; never compiled. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Position(double x, double y) implements Serializable {

    public static final Position ORIGIN = new Position(0, 0);
}
