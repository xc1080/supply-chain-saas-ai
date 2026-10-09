-- Legacy shop tables inherited the database collation. Transaction tables explicitly use
-- utf8mb4, whose MySQL 8 default is utf8mb4_0900_ai_ci. Keep join identifiers compatible.
ALTER TABLE commerce_shop CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE commerce_shop_member CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE commerce_product_shop CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
