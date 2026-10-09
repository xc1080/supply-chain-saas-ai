class BusinessException(Exception):

    def __init__(self, code: int, message: str, data=None):

        super().__init__(message)

        self.code = code

        self.message = message

        self.data = data
