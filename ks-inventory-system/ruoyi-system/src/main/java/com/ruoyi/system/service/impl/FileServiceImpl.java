package com.ruoyi.system.service.impl;

import com.ruoyi.common.core.domain.entity.File;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.FileMapper;
import com.ruoyi.system.mapper.WarehouseMapper;
import com.ruoyi.system.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.validation.Validator;
import java.util.List;

/**
 * 用户 业务层处理
 *
 * @author KrityCat
 */
@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    @Autowired
    protected Validator validator;

    @Autowired
    private WarehouseMapper warehouseMapper;

    @Autowired
    private FileMapper fileMapper;

    /**
     * 打印文件上传
     */
    @Override
    @Transactional
    public File upload(String fileName, String fileUrl, String createBy) {
        if (StringUtils.isEmpty(createBy)) {
            throw new ServiceException("上传文件缺少创建者");
        }
        File uploaded = new File();
        uploaded.setFileName(fileName);
        uploaded.setFileUrl(fileUrl);
        uploaded.setCreateBy(createBy);
        if (fileMapper.upload(uploaded) != 1 || uploaded.getFileId() == null) {
            throw new ServiceException("保存上传文件记录失败");
        }
        return uploaded;
    }

    /**
     * 打印文件查询
     */
    @Override
    public List<File> fileQuery(File bo) {
        return fileMapper.fileQuery(bo);
    }

    /**
     * 打印文件详情查询
     */
    @Override
    public File selectFileById(Long fileId) {
        return fileMapper.selectFileById(fileId);
    }

    /**
     * 打印文件修改
     */
    @Override
    @Transactional
    public int updateFileName(File bo) {
        return fileMapper.updateFileName(bo);
    }

    /**
     * 打印文件删除
     */
    @Override
    @Transactional
    public int deleteFileByIds(Long[] fileId) {
        return fileMapper.deleteFileByIds(fileId);
    }

}
