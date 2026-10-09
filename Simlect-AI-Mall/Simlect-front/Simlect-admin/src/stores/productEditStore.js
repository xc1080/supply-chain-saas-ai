import { ref } from 'vue'
import { defineStore } from 'pinia'

export const useProductEditStore = defineStore('productEditStore', () => {
    const productPropertyList = ref([])
    const skuList = ref([])
    const skuData = ref(new Map())

    const categoryPropertyTemplates = ref([])

    const excludedSkuHashes = ref(new Set())

    const resetSkuState = () => {
        productPropertyList.value = []
        skuList.value = []
        skuData.value = new Map()
        categoryPropertyTemplates.value = []
        excludedSkuHashes.value = new Set()
    }

    return {
        productPropertyList,
        skuList,
        skuData,
        categoryPropertyTemplates,
        excludedSkuHashes,
        resetSkuState,
    }
})
