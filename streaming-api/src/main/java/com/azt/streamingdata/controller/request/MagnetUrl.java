package com.azt.streamingdata.controller.request;

import jakarta.validation.constraints.NotBlank;

public record MagnetUrl (
        @NotBlank(message = "Not magnet null")
        String magnetUrl
) { }
