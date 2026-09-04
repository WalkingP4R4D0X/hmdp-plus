-- Phase 1 / Agent integration test data.
-- Safe to re-run: all rows use INSERT IGNORE and counters are derived from data.
SET NAMES utf8mb4;

-- Voucher ids follow the project's sharding rule: odd ids -> hmdp_1, even ids -> hmdp_0.
INSERT IGNORE INTO hmdp_0.tb_voucher_0
  (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES
  (4, 4, '情侣双人晚餐秒杀券', '晚餐套餐立减40元', '周一至周日 17:00-21:00 使用，每单限用1张', 9900, 13900, 1, 1);
INSERT IGNORE INTO hmdp_0.tb_voucher_1
  (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES
  (2, 1, '100元美食代金券', '满120元可用', '全场通用，不与其他优惠同享', 8500, 10000, 0, 1),
  (6, 8, '寿司双人套餐券', '招牌寿司拼盘', '周一至周五可用，节假日除外', 6800, 9000, 0, 1);
INSERT IGNORE INTO hmdp_1.tb_voucher_1
  (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES
  (3, 5, '海底捞150元代金券', '火锅聚餐更划算', '满200元可用，每桌限用1张', 13000, 15000, 0, 1);
INSERT IGNORE INTO hmdp_1.tb_voucher_0
  (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES
  (5, 3, '新白鹿双人套餐秒杀券', '经典菜品组合', '每日 11:00-14:00、17:00-20:00 使用', 5900, 8800, 1, 1);

-- Seckill rows use the same voucher_id sharding rule and remain valid during the test window.
INSERT IGNORE INTO hmdp_0.tb_seckill_voucher_0
  (id, voucher_id, init_stock, stock, allowed_levels, min_level, begin_time, end_time)
VALUES
  (1987043235650076676, 4, 50, 50, '1,2,3', 1, '2026-08-01 00:00:00', '2026-12-31 23:59:59');
INSERT IGNORE INTO hmdp_0.tb_seckill_voucher_1
  (id, voucher_id, init_stock, stock, allowed_levels, min_level, begin_time, end_time)
VALUES
  (1987043235650076678, 2, 80, 80, '1,2,3', 1, '2026-08-01 00:00:00', '2026-12-31 23:59:59');
INSERT IGNORE INTO hmdp_1.tb_seckill_voucher_0
  (id, voucher_id, init_stock, stock, allowed_levels, min_level, begin_time, end_time)
VALUES
  (1987043235650076677, 5, 60, 60, '1,2,3', 1, '2026-08-01 00:00:00', '2026-12-31 23:59:59');

-- Broadcast tables are kept in both databases by the existing ShardingSphere setup.
INSERT IGNORE INTO hmdp_0.tb_blog
  (id, shop_id, user_id, title, images, content, liked, comments)
VALUES
  (1001, 1, 1987041610793484289, '拱墅区宝藏茶餐厅｜人均百元吃得很满足', '/imgs/blogs/blog1.jpg', '周末和朋友来吃港式茶点，奶茶香浓，菠萝包外酥里软，适合轻松约会。', 3, 3),
  (1002, 4, 1987042234935279617, '远洋乐堤港浪漫晚餐｜环境和味道都在线', '/imgs/blogs/blog1.jpg', '灯光很有氛围，牛排火候不错，建议提前预约靠窗位置。', 5, 3),
  (1003, 8, 1987042505555968001, '运河上街寿司探店｜两个人吃刚刚好', '/imgs/blogs/blog1.jpg', '刺身新鲜，拼盘分量适中，工作日晚餐性价比很高。', 2, 3),
  (1004, 10, 1987041610793484289, '周末唱歌攻略｜拱墅区平价 KTV 推荐', '/imgs/blogs/blog1.jpg', '包间音响和服务都不错，适合朋友聚会，晚上时段记得提前订房。', 4, 3);
INSERT IGNORE INTO hmdp_1.tb_blog SELECT * FROM hmdp_0.tb_blog WHERE id IN (1001,1002,1003,1004);

INSERT IGNORE INTO hmdp_0.tb_blog_comments
  (id, user_id, blog_id, parent_id, answer_id, content, liked, status)
VALUES
  (2001, 1987042234935279617, 1001, 0, 0, '这家奶茶确实很香，下次想试试晚餐。', 2, 0),
  (2002, 1987042505555968001, 1001, 0, 0, '请问周末需要排队吗？', 1, 0),
  (2003, 1987041610793484289, 1001, 2002, 2002, '我们周六下午去，等了大约十分钟。', 1, 0),
  (2004, 1987041610793484289, 1002, 0, 0, '靠窗位置拍照很好看，收藏了。', 3, 0),
  (2005, 1987042505555968001, 1002, 0, 0, '牛排推荐几分熟？', 0, 0),
  (2006, 1987042234935279617, 1002, 2005, 2005, '我点的五分熟，口感比较嫩。', 1, 0),
  (2007, 1987041610793484289, 1003, 0, 0, '寿司拼盘看起来很丰富，人均大概多少？', 2, 0),
  (2008, 1987042234935279617, 1003, 0, 0, '两个人一百多，工作日很划算。', 1, 0),
  (2009, 1987042505555968001, 1003, 0, 0, '交通方便吗？', 0, 0),
  (2010, 1987041610793484289, 1004, 0, 0, '周末聚会可以考虑这家。', 2, 0),
  (2011, 1987042234935279617, 1004, 0, 0, '有学生优惠吗？', 1, 0),
  (2012, 1987042505555968001, 1004, 2011, 2011, '店里偶尔会有团购券，可以看看优惠券页。', 1, 0);
INSERT IGNORE INTO hmdp_1.tb_blog_comments SELECT * FROM hmdp_0.tb_blog_comments WHERE id BETWEEN 2001 AND 2012;

UPDATE hmdp_0.tb_blog b SET b.comments = (SELECT COUNT(*) FROM hmdp_0.tb_blog_comments c WHERE c.blog_id = b.id) WHERE b.id IN (1001,1002,1003,1004);
UPDATE hmdp_1.tb_blog b SET b.comments = (SELECT COUNT(*) FROM hmdp_1.tb_blog_comments c WHERE c.blog_id = b.id) WHERE b.id IN (1001,1002,1003,1004);
