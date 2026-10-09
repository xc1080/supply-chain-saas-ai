package com.simlect.entity.query;

public class UserAddressQuery extends BaseParam {

	private String addressId;

	private String addressIdFuzzy;

	private String userId;

	private String userIdFuzzy;

	private String address;

	private String addressFuzzy;

	private String addressee;

	private String addresseeFuzzy;

	private String phone;

	private String phoneFuzzy;

	private Integer defaultType;

	public void setAddressId(String addressId){
		this.addressId = addressId;
	}

	public String getAddressId(){
		return this.addressId;
	}

	public void setAddressIdFuzzy(String addressIdFuzzy){
		this.addressIdFuzzy = addressIdFuzzy;
	}

	public String getAddressIdFuzzy(){
		return this.addressIdFuzzy;
	}

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

	public void setAddress(String address){
		this.address = address;
	}

	public String getAddress(){
		return this.address;
	}

	public void setAddressFuzzy(String addressFuzzy){
		this.addressFuzzy = addressFuzzy;
	}

	public String getAddressFuzzy(){
		return this.addressFuzzy;
	}

	public void setAddressee(String addressee){
		this.addressee = addressee;
	}

	public String getAddressee(){
		return this.addressee;
	}

	public void setAddresseeFuzzy(String addresseeFuzzy){
		this.addresseeFuzzy = addresseeFuzzy;
	}

	public String getAddresseeFuzzy(){
		return this.addresseeFuzzy;
	}

	public void setPhone(String phone){
		this.phone = phone;
	}

	public String getPhone(){
		return this.phone;
	}

	public void setPhoneFuzzy(String phoneFuzzy){
		this.phoneFuzzy = phoneFuzzy;
	}

	public String getPhoneFuzzy(){
		return this.phoneFuzzy;
	}

	public void setDefaultType(Integer defaultType){
		this.defaultType = defaultType;
	}

	public Integer getDefaultType(){
		return this.defaultType;
	}

}
