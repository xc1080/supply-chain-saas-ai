package com.simlect.biz;

import java.util.List;

import com.simlect.constants.Constants;
import com.simlect.entity.query.UserInfoQuery;
import com.simlect.entity.po.UserInfo;
import com.simlect.entity.vo.PaginationResultVO;
import jakarta.validation.constraints.*;

public interface UserInfoService {

	List<UserInfo> findListByParam(UserInfoQuery param);

	Integer findCountByParam(UserInfoQuery param);

	PaginationResultVO<UserInfo> findListByPage(UserInfoQuery param);

	Integer add(UserInfo bean);

	Integer addBatch(List<UserInfo> listBean);

	Integer addOrUpdateBatch(List<UserInfo> listBean);

	Integer updateByParam(UserInfo bean,UserInfoQuery param);

	Integer deleteByParam(UserInfoQuery param);

	UserInfo getUserInfoByUserId(String userId);

	Integer updateUserInfoByUserId(UserInfo bean,String userId);

	Integer deleteUserInfoByUserId(String userId);

	UserInfo getUserInfoByEmail(String email);

	Integer updateUserInfoByEmail(UserInfo bean,String email);

	Integer deleteUserInfoByEmail(String email);

	UserInfo getUserInfoByNickName(String nickName);

	Integer updateUserInfoByNickName(UserInfo bean,String nickName);

	Integer deleteUserInfoByNickName(String nickName);

    void register(@NotEmpty @Email @Size(max = 150) String emali, @NotEmpty @Size(max = 20) String nickName, @NotEmpty @Pattern(regexp = Constants.REGEX_PASSWORD) String registerPassword, @NotEmpty String checkCode);

	void updateUserInfo(String userId, String avatar, @NotEmpty String nickName, @NotNull Integer sex);

    void updatePassword(String userId, String oldPassword, String password);

    void forgetPassword(@NotEmpty @Email @Size(max = 150) String email, @NotEmpty @Pattern(regexp = Constants.REGEX_PASSWORD) String newPassword, @NotEmpty String checkCode);
}
