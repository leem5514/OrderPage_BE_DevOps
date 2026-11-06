package com.example.ordersystem.common.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class AwsS3Configs { 
    @Value("${cloud.aws.region.static}")
    private String region;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(region))
                // EKS에서는 ServiceAccount의 IRSA 임시 자격증명을 사용한다.
                // 로컬에서는 SDK 표준 환경변수/프로파일을 같은 체인으로 읽는다.
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
