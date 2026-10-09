from pydantic import BaseModel, Field

class MessageSendDTO(BaseModel):
    message_id: int | None = Field(None, alias="messageId")
    user_id: str | None = Field(None, alias="userId")
    user_message: str | None = Field(None, alias="userMessage")
    assistant_message: str | None = Field(None, alias="assistantMessage")
    biz_type: str | None = Field(None, alias="bizType")
    biz_id: str | None = Field(None, alias="bizId")
    message_type: str = Field("agent", alias="messageType")
    out_put_type: int = Field(0, alias="outPutType")
    notification_id: str | None = Field(None, alias="notificationId")
    title: str | None = None
    content: str | None = None
    create_time: str | None = Field(None, alias="createTime")

    model_config = {"populate_by_name": True}

    def to_ws_dict(self) -> dict:
        d = self.model_dump(by_alias=True, exclude_none=True)

        if d.get("messageId") is not None:
            d["messageId"] = str(d["messageId"])
        return d
