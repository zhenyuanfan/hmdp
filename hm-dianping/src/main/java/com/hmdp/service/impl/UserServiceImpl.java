package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;
import static com.hmdp.utils.SystemConstants.VERIFY_CODE;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.判断手机号格式是否正确
        if(RegexUtils.isPhoneInvalid( phone)){
            //2.不正确返回错误信息
           return Result.fail("手机号格式错误");
        }
        //3.生成验证码，正确
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session
        session.setAttribute(VERIFY_CODE,code);
        //5.发送验证码
        log.debug("发送验证码成功，验证码为：{}",code);
        //6.返回结果
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.获取并且验证手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid( phone)){
            return Result.fail("手机号格式错误");
        }
        //2.获取并且核对验证码
        String code = loginForm.getCode();
        Object cacheCode = session.getAttribute(VERIFY_CODE);
        if(cacheCode == null || !cacheCode.toString().equals(code)){
            return Result.fail("验证码错误");
        }
        //3.查询数据库看是否有用户
        User user = query().eq("phone", phone).one();
        //4.不存在存在用户，创建用户
        if(user == null){
            user = createUserWithPhone(phone);
        }
        //5.保存用户到session中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
