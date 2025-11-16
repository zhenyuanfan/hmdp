package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Test
    public void shopToRedisShop() throws InterruptedException{
        shopService.saveShop2Redis(1L, 10L);
    }
}
