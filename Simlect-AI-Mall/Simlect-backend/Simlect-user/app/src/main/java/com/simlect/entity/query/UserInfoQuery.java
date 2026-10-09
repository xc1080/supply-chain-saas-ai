package com.simlect.entity.query;

import java.util.Date;

public class UserInfoQuery extends BaseParam {

	private String userId;

	private String userIdFuzzy;

	private String nickName;

	private String nickNameFuzzy;

	private String avatar;

	private String avatarFuzzy;

	private String email;

	private String emailFuzzy;

	private String password;

	private String passwordFuzzy;

	private Integer sex;

	private String joinTime;

	private String joinTimeStart;

	private String joinTimeEnd;

	private String lastLoginTime;

	private String lastLoginTimeStart;

	private String lastLoginTimeEnd;

	private String lastLoginIp;

	private String lastLoginIpFuzzy;

	private Integer status;

	public void setUserId(String userId){
		this.userId = userId;
	}

	public String getUserId(){
		return this.userId;
	}

	public void setUserIdFuzzy(String userIdFuzzy){
		this.userIdFuzzy = userIdFuzzy;
	}

	public String getUserIdFuzzy(){
		return this.userIdFuzzy;
	}

	public void setNickName(String nickName){
		this.nickName = nickName;
	}

	public String getNickName(){
		return this.nickName;
	}

	public void setNickNameFuzzy(String nickNameFuzzy){
		this.nickNameFuzzy = nickNameFuzzy;
	}

	public String getNickNameFuzzy(){
		return this.nickNameFuzzy;
	}

	public void setAvatar(String avatar){
		this.avatar = avatar;
	}

	public String getAvatar(){
		return this.avatar;
	}

	public void setAvatarFuzzy(String avatarFuzzy){
		this.avatarFuzzy = avatarFuzzy;
	}

	public String getAvatarFuzzy(){
		return this.avatarFuzzy;
	}

	public void setEmail(String email){
		this.email = email;
	}

	public String getEmail(){
		return this.email;
	}

	public void setEmailFuzzy(String emailFuzzy){
		this.emailFuzzy = emailFuzzy;
	}

	public String getEmailFuzzy(){
		return this.emailFuzzy;
	}

	public void setPassword(String password){
		this.password = password;
	}

	public String getPassword(){
		return this.password;
	}

	public void setPasswordFuzzy(String passwordFuzzy){
		this.passwordFuzzy = passwordFuzzy;
	}

	public String getPasswordFuzzy(){
		return this.passwordFuzzy;
	}

	public void setSex(Integer sex){
		this.sex = sex;
	}

	public Integer getSex(){
		return this.sex;
	}

	public void setJoinTime(String joinTime){
		this.joinTime = joinTime;
	}

	public String getJoinTime(){
		return this.joinTime;
	}

	public void setJoinTimeStart(String joinTimeStart){
		this.joinTimeStart = joinTimeStart;
	}

	public String getJoinTimeStart(){
		return this.joinTimeStart;
	}
	public void setJoinTimeEnd(String joinTimeEnd){
		this.joinTimeEnd = joinTimeEnd;
	}

	public String getJoinTimeEnd(){
		return this.joinTimeEnd;
	}

	public void setLastLoginTime(String lastLoginTime){
		this.lastLoginTime = lastLoginTime;
	}

	public String getLastLoginTime(){
		return this.lastLoginTime;
	}

	public void setLastLoginTimeStart(String lastLoginTimeStart){
		this.lastLoginTimeStart = lastLoginTimeStart;
	}

	public String getLastLoginTimeStart(){
		return this.lastLoginTimeStart;
	}
	public void setLastLoginTimeEnd(String lastLoginTimeEnd){
		this.lastLoginTimeEnd = lastLoginTimeEnd;
	}

	public String getLastLoginTimeEnd(){
		return this.lastLoginTimeEnd;
	}

	public void setLastLoginIp(String lastLoginIp){
		this.lastLoginIp = lastLoginIp;
	}

	public String getLastLoginIp(){
		return this.lastLoginIp;
	}

	public void setLastLoginIpFuzzy(String lastLoginIpFuzzy){
		this.lastLoginIpFuzzy = lastLoginIpFuzzy;
	}

	public String getLastLoginIpFuzzy(){
		return this.lastLoginIpFuzzy;
	}

	public void setStatus(Integer status){
		this.status = status;
	}

	public Integer getStatus(){
		return this.status;
	}

}
