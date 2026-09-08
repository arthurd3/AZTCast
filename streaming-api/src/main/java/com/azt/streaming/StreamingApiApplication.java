package com.azt.streaming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class StreamingApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamingApiApplication.class, args);
    }

}
