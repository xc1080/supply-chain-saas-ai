package com.simlect.api.dto;

import java.io.Serializable;

public class ImageUploadResultDTO implements Serializable {

    private String path;

    private Boolean pendingReview;

    public ImageUploadResultDTO() {
    }

    public ImageUploadResultDTO(String path, Boolean pendingReview) {
        this.path = path;
        this.pendingReview = pendingReview;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Boolean getPendingReview() {
        return pendingReview;
    }

    public void setPendingReview(Boolean pendingReview) {
        this.pendingReview = pendingReview;
    }
}
