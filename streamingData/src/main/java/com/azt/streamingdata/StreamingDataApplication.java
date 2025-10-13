package com.azt.streamingdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class StreamingDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamingDataApplication.class, args);
    }

}
