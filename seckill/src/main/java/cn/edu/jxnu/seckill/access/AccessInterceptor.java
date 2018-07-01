package cn.edu.jxnu.seckill.access;

import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import com.alibaba.fastjson.JSON;

import cn.edu.jxnu.seckill.domain.SeckillUser;
import cn.edu.jxnu.seckill.redis.AccessKey;
import cn.edu.jxnu.seckill.redis.RedisService;
import cn.edu.jxnu.seckill.result.CodeMsg;
import cn.edu.jxnu.seckill.result.Result;
import cn.edu.jxnu.seckill.service.SeckillUserService;
import cn.edu.jxnu.seckill.util.CookieUtil;

@Service
public class AccessInterceptor extends HandlerInterceptorAdapter {

	@Autowired
	private SeckillUserService userService;

	@Autowired
	private RedisService redisService;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		if (handler instanceof HandlerMethod) {
			SeckillUser seckillUser = getUser(request, response);
			UserContext.setUser(seckillUser);

			HandlerMethod method = (HandlerMethod) handler;
			AccessLimit accessLimit = method.getMethodAnnotation(AccessLimit.class);
			if (accessLimit == null) {
				return true;
			}

			int seconds = accessLimit.seconds();
			int maxCount = accessLimit.maxCount();
			boolean needLogin = accessLimit.needLogin();
			String key = request.getRequestURI();
			if (needLogin) {
				if (seckillUser == null) {
					render(response, CodeMsg.SERVER_ERROR);
					return false;
				}
				key += "_" + seckillUser.getId();
			}

			// martin fowler,重构-改善既有代码的设计
			AccessKey accessKey = AccessKey.withExpire(seconds);
			// 查询访问的次数
			Integer count = redisService.get(accessKey, key, Integer.class);
			if (count == null) {
				redisService.set(accessKey, key, 1);
			} else if (count < maxCount) {
				redisService.incr(accessKey, key);
			} else {
				render(response, CodeMsg.ACCESS_LIMIT_REACHED);
				return false;
			}
		}

		return true;
	}

	private void render(HttpServletResponse response, CodeMsg serverError) throws IOException {
		response.setContentType("application/json;charset=UTF-8");
		OutputStream outputStream = response.getOutputStream();
		String str = JSON.toJSONString(Result.error(serverError));
		outputStream.write(str.getBytes("UTF-8"));
		outputStream.flush();
		outputStream.close();
	}

	private SeckillUser getUser(HttpServletRequest request, HttpServletResponse response) {
		String paramToken = request.getParameter(SeckillUserService.COOKIE_NAME_TOKEN);
		String cookieToken = CookieUtil.getCookieValue(request, SeckillUserService.COOKIE_NAME_TOKEN);

		if (StringUtils.isEmpty(cookieToken) && StringUtils.isEmpty(paramToken)) {
			return null;
		}

		String token = StringUtils.isEmpty(paramToken) ? cookieToken : paramToken;
		return userService.getByToken(response, token);
	}
}
