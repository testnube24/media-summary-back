package com.mediasummary.service;

import org.springframework.web.multipart.MultipartFile;

public interface ObjectStorageService {

    StoredObject uploadAudio(MultipartFile file);

    @lombok.Value
    class StoredObject {
        String objectPath;
        String publicUrl;
    }
}

