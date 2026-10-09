package com.simlect.entity.query;

import java.math.BigDecimal;
import java.util.Date;

public class PayTradeRecordQuery extends BaseParam {

	private String tradeId;

	private String tradeIdFuzzy;

	private String orderId;

	private String orderIdFuzzy;

	private String userId;

	private String userIdFuzzy;

	private String payOrderId;

	private String payOrderIdFuzzy;

	private String channelOrderId;

	private String channelOrderIdFuzzy;

	private String payChannel;

	private String payChannelFuzzy;

	private BigDecimal payAmount;

	private Integer tradeStatus;

	private Date payTime;

	private String payTimeStart;
	private String payTimeEnd;

	private Date createTime;

	private String createTimeStart;
	private String createTimeEnd;

	public void setTradeId(String tradeId){ this.tradeId = tradeId; }
	public String getTradeId(){ return this.tradeId; }

	public void setTradeIdFuzzy(String tradeIdFuzzy){ this.tradeIdFuzzy = tradeIdFuzzy; }
	public String getTradeIdFuzzy(){ return this.tradeIdFuzzy; }

	public void setOrderId(String orderId){ this.orderId = orderId; }
	public String getOrderId(){ return this.orderId; }

	public void setOrderIdFuzzy(String orderIdFuzzy){ this.orderIdFuzzy = orderIdFuzzy; }
	public String getOrderIdFuzzy(){ return this.orderIdFuzzy; }

	public void setUserId(String userId){ this.userId = userId; }
	public String getUserId(){ return this.userId; }

	public void setUserIdFuzzy(String userIdFuzzy){ this.userIdFuzzy = userIdFuzzy; }
	public String getUserIdFuzzy(){ return this.userIdFuzzy; }

	public void setPayOrderId(String payOrderId){ this.payOrderId = payOrderId; }
	public String getPayOrderId(){ return this.payOrderId; }

	public void setPayOrderIdFuzzy(String payOrderIdFuzzy){ this.payOrderIdFuzzy = payOrderIdFuzzy; }
	public String getPayOrderIdFuzzy(){ return this.payOrderIdFuzzy; }

	public void setChannelOrderId(String channelOrderId){ this.channelOrderId = channelOrderId; }
	public String getChannelOrderId(){ return this.channelOrderId; }

	public void setChannelOrderIdFuzzy(String channelOrderIdFuzzy){ this.channelOrderIdFuzzy = channelOrderIdFuzzy; }
	public String getChannelOrderIdFuzzy(){ return this.channelOrderIdFuzzy; }

	public void setPayChannel(String payChannel){ this.payChannel = payChannel; }
	public String getPayChannel(){ return this.payChannel; }

	public void setPayChannelFuzzy(String payChannelFuzzy){ this.payChannelFuzzy = payChannelFuzzy; }
	public String getPayChannelFuzzy(){ return this.payChannelFuzzy; }

	public void setPayAmount(BigDecimal payAmount){ this.payAmount = payAmount; }
	public BigDecimal getPayAmount(){ return this.payAmount; }

	public void setTradeStatus(Integer tradeStatus){ this.tradeStatus = tradeStatus; }
	public Integer getTradeStatus(){ return this.tradeStatus; }

	public void setPayTime(Date payTime){ this.payTime = payTime; }
	public Date getPayTime(){ return this.payTime; }

	public void setPayTimeStart(String payTimeStart){ this.payTimeStart = payTimeStart; }
	public String getPayTimeStart(){ return this.payTimeStart; }
	public void setPayTimeEnd(String payTimeEnd){ this.payTimeEnd = payTimeEnd; }
	public String getPayTimeEnd(){ return this.payTimeEnd; }

	public void setCreateTime(Date createTime){ this.createTime = createTime; }
	public Date getCreateTime(){ return this.createTime; }

	public void setCreateTimeStart(String createTimeStart){ this.createTimeStart = createTimeStart; }
	public String getCreateTimeStart(){ return this.createTimeStart; }
	public void setCreateTimeEnd(String createTimeEnd){ this.createTimeEnd = createTimeEnd; }
	public String getCreateTimeEnd(){ return this.createTimeEnd; }

}
