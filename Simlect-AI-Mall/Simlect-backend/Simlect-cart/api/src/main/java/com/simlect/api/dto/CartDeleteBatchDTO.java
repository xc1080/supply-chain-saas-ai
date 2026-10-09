package com.simlect.api.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class CartDeleteBatchDTO implements Serializable {
    private List<CartDeleteItemDTO> items = new ArrayList<>();

    public CartDeleteBatchDTO() {}

    public CartDeleteBatchDTO(List<CartDeleteItemDTO> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }

    public List<CartDeleteItemDTO> getItems() { return items; }
    public void setItems(List<CartDeleteItemDTO> items) { this.items = items; }
}
