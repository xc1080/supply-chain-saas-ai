import { ElMessageBox } from 'element-plus'

const ios26MessageBoxOptions = {
    closeOnClickModal: false,
    distinguishCancelAndClose: true,
    autofocus: false,
    center: true,
}

const Confirm = ({ message, okfun, showCancelBtn = true, showClose = true, okText = '确定', cancelText = '取消', cancelfun }) => {
    ElMessageBox.confirm(message, '提示', {
        ...ios26MessageBoxOptions,
        confirmButtonText: okText,
        cancelButtonText: cancelText,
        showCancelButton: showCancelBtn,
        showClose: showClose,
    }).then(async () => {
        if (okfun) {
            okfun();
        }
    }).catch((action) => {
        if (action == "cancel" && cancelfun) {
            cancelfun()
        }
    });
};

const Alert = (msg, okfun) => {
    ElMessageBox.alert(msg, '确认', {
        ...ios26MessageBoxOptions,
        confirmButtonText: '确定',
        callback: (action) => {
            if (action == "confirm" && okfun) {
                okfun();
            }
        },
    })
}
export {
    Confirm,
    Alert
}
