package com.ruoyi.system.mapper;

import com.ruoyi.common.core.domain.entity.File;

import java.util.List;

public interface FileMapper {


    /**
     * 打印文件查询
     */
    List<File> fileQuery(File bo);

    /**
     * 打印文件详情查询
     */
    File selectFileById(Long fileId);

    /**
     * 打印文件上传
     */
    int upload(File bo);

    /**
     * 打印文件修改
     */
    int updateFileName(File bo);

    /**
     * 打印文件删除
     */
    int deleteFileByIds(Long[] fileId);


}
