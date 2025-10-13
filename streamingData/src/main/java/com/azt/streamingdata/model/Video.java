package com.azt.streamingdata.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Video {
    private String videoId;
    private String videoTitle;
    private String videoDescription;
    private String contentType;
    private String videoPath;
}
