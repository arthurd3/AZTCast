package com.azt.streamingdata.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.awt.*;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class VideoInfo {
    private int id;
    private String title;
    private String director;
    private Image image;
    private int duration;
    private List<String> languages;
}
