package com.stonewu.agenteam.configuration.file;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileStorageModeTest {
    @Test
    void unconfiguredStorageUsesLocalFilesInEveryProfile() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        assertEquals(FileStorageMode.LOCAL, FileStorageMode.resolve(environment));
        assertEquals(FileStorageMode.LOCAL, FileStorageMode.resolve(environment.withProperty("files.s3.endpoint", " ")));
    }

    @Test
    void configuredStorageRequiresCompleteCredentialsAndNeverSilentlyChangesDestination() {
        var environment = new MockEnvironment().withProperty("files.s3.bucket", "documents");
        assertThrows(IllegalArgumentException.class, () -> FileStorageMode.resolve(environment));
        environment.withProperty("files.s3.access-key-id", "test-id").withProperty("files.s3.secret-access-key", "test-secret");
        assertEquals(FileStorageMode.S3, FileStorageMode.resolve(environment));
        assertEquals(FileStorageMode.LOCAL, FileStorageMode.resolve(environment.withProperty("files.storage", "local")));
        assertThrows(IllegalArgumentException.class, () -> FileStorageMode.resolve(new MockEnvironment().withProperty("files.storage", "s3")));
    }
}
