package com.simlect.api.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.math.BigDecimal;

@Data
@Document(indexName = "simlect-index")
@Setting(settingPath = "es-settings.json")
public class ProductInfoDTO {

    @Field(type = FieldType.Keyword)
    @Id
    private String productId;

    @MultiField(
            mainField = @Field(
                    type = FieldType.Text,
                    analyzer = "ik_max_word",      // 索引时：细粒度分词
                    searchAnalyzer = "ik_smart"     // 搜索时：智能分词
            ),
            otherFields = {
                    // 子字段：keyword，用于精确匹配、排序、聚合
                    @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256),
            }
    )
    private String productName;

    @Field(
            type = FieldType.Text,
            analyzer = "ik_max_word",
            searchAnalyzer = "ik_smart"
    )
    private String productDesc;

    @Field(type = FieldType.Keyword, index = false)
    private String cover;

    @Field(type = FieldType.Keyword)
    private String categoryId;

    @Field(type = FieldType.Scaled_Float, scalingFactor = 100)
    private BigDecimal minPrice;

    @Field(type = FieldType.Scaled_Float, scalingFactor = 100)
    private BigDecimal maxPrice;

    @Field(type = FieldType.Integer)
    private Integer totalSale;

}
