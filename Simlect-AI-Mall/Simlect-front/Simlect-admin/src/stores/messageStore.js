import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

export const useMessageStore = defineStore('messgeStore', () => {


    const message = ref();

    const onMessage = (_message) => {
        message.value = _message;
    }

    return { message, onMessage }
})
