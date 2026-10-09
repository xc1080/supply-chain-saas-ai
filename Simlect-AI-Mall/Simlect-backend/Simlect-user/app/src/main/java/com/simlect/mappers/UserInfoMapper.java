package com.simlect.mappers;

import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface UserInfoMapper<T,P> extends BaseMapper<T,P> {

	List<String> selectAllUserId();

	List<T> selectBriefByUserIds(@Param("userIds") List<String> userIds);

	 Integer updateByUserId(@Param("bean") T t,@Param("userId") String userId);

	 Integer deleteByUserId(@Param("userId") String userId);

	 T selectByUserId(@Param("userId") String userId);

	 Integer updateByEmail(@Param("bean") T t,@Param("email") String email);

	 Integer deleteByEmail(@Param("email") String email);

	 T selectByEmail(@Param("email") String email);

	 Integer updateByNickName(@Param("bean") T t,@Param("nickName") String nickName);

	 Integer deleteByNickName(@Param("nickName") String nickName);

	 T selectByNickName(@Param("nickName") String nickName);

}
