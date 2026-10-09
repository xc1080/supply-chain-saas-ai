package com.simlect.entity.po;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Date;
import com.simlect.entity.enums.DateTimePatternEnum;
import com.simlect.utils.DateUtil;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;

public class RagQuestion implements Serializable {

	private Integer questionId;

	private String question;

	private String similarQuestion;

	private String answer;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date createTime;

	public void setQuestionId(Integer questionId){
		this.questionId = questionId;
	}

	public Integer getQuestionId(){
		return this.questionId;
	}

	public void setQuestion(String question){
		this.question = question;
	}

	public String getQuestion(){
		return this.question;
	}

	public void setSimilarQuestion(String similarQuestion){
		this.similarQuestion = similarQuestion;
	}

	public String getSimilarQuestion(){
		return this.similarQuestion;
	}

	public void setAnswer(String answer){
		this.answer = answer;
	}

	public String getAnswer(){
		return this.answer;
	}

	public void setCreateTime(Date createTime){
		this.createTime = createTime;
	}

	public Date getCreateTime(){
		return this.createTime;
	}

	@Override
	public String toString (){
		return "自增ID:"+(questionId == null ? "空" : questionId)+"，问题:"+(question == null ? "空" : question)+"，相似问题:"+(similarQuestion == null ? "空" : similarQuestion)+"，答案:"+(answer == null ? "空" : answer)+"，创建时间:"+(createTime == null ? "空" : DateUtil.format(createTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern()));
	}
}
