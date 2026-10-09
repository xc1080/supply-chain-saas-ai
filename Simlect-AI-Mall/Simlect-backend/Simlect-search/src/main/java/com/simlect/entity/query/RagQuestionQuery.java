package com.simlect.entity.query;

import java.util.Date;

public class RagQuestionQuery extends BaseParam {

	private Integer questionId;

	private String question;

	private String questionFuzzy;

	private String similarQuestion;

	private String similarQuestionFuzzy;

	private String answer;

	private String answerFuzzy;

	private String createTime;

	private String createTimeStart;

	private String createTimeEnd;

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

	public void setQuestionFuzzy(String questionFuzzy){
		this.questionFuzzy = questionFuzzy;
	}

	public String getQuestionFuzzy(){
		return this.questionFuzzy;
	}

	public void setSimilarQuestion(String similarQuestion){
		this.similarQuestion = similarQuestion;
	}

	public String getSimilarQuestion(){
		return this.similarQuestion;
	}

	public void setSimilarQuestionFuzzy(String similarQuestionFuzzy){
		this.similarQuestionFuzzy = similarQuestionFuzzy;
	}

	public String getSimilarQuestionFuzzy(){
		return this.similarQuestionFuzzy;
	}

	public void setAnswer(String answer){
		this.answer = answer;
	}

	public String getAnswer(){
		return this.answer;
	}

	public void setAnswerFuzzy(String answerFuzzy){
		this.answerFuzzy = answerFuzzy;
	}

	public String getAnswerFuzzy(){
		return this.answerFuzzy;
	}

	public void setCreateTime(String createTime){
		this.createTime = createTime;
	}

	public String getCreateTime(){
		return this.createTime;
	}

	public void setCreateTimeStart(String createTimeStart){
		this.createTimeStart = createTimeStart;
	}

	public String getCreateTimeStart(){
		return this.createTimeStart;
	}
	public void setCreateTimeEnd(String createTimeEnd){
		this.createTimeEnd = createTimeEnd;
	}

	public String getCreateTimeEnd(){
		return this.createTimeEnd;
	}

}
