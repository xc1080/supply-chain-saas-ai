package com.simlect.api.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class SignDateSyncResultVO implements Serializable {

    private int totalInDb;

    private int syncedUsers;

    private int syncedDates;

    private int skipped;

    private int rejected;

    private int diffCount;

    private int supplementedDates;
}
