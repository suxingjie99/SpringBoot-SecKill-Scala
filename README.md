### Scala语言实现的慕课网秒杀系统【暂时完成，待优化】

        语言与主体框架支持
            SpringBoot1.5.8
            Scala2.12.3
            JDK1.8
            
        其他技术
        
            Swagger
            RabbitMQ
            Redis
            Validation JSR303
            Mybatis
            Thymeleaf
            Bootstrap
            fastjson
            commons-codec
            druid
            
注意：[seckill分支是Java版本](https://github.com/jxnu-liguobin/SpringBoot-SecKill-Scala/tree/seckill)  
### 实现技术点

1. 两次MD5加密
        
        将用户输入的密码和固定Salt通过MD5加密生成第一次加密后的密码，再讲该密码和随机生成的Salt通过MD5进行第二次加密
        最后将第二次加密后的密码和第一次的固定Salt存数据库
        
        好处：
        第一次作用：防止用户明文密码在网络进行传输
        第二次作用：防止数据库被盗，避免通过MD5反推出密码，双重保险
        
2. session共享
        
        验证用户账号密码都正确情况下，通过UUID生成唯一id作为token，再将token作为key、用户信息作为value模拟session存储到redis
        同时将token存储到cookie，保存登录状态

        好处： 
        在分布式集群情况下，服务器间需要同步，定时同步各个服务器的session信息，会因为延迟到导致session不一致
        使用redis把session数据集中存储起来，解决session不一致问题。

3. JSR303自定义参数验证
        
        使用JSR303自定义校验器，实现对用户账号、密码的验证，使得验证逻辑从业务代码中脱离出来。

4. 全局异常统一处理
        
        通过拦截所有异常，对各种异常进行相应的处理，当遇到异常就逐层上抛，一直抛到最终由一个统一的、专门负责异常处理的地方处理
        这有利于对异常的维护。

5. 页面缓存 + 对象缓存
        
        页面缓存：通过在手动渲染得到的html页面缓存到redis
        对象缓存：包括对用户信息、商品信息、订单信息和token等数据进行缓存，利用缓存来减少对数据库的访问，大大加快查询速度。
6. 页面静态化
        
        对商品详情和订单详情进行页面静态化处理，页面是存在html，动态数据是通过接口从服务端获取，实现前后端分离
        静态页面无需连接数据库打开速度较动态页面会有明显提高

7. 本地标记 + redis预处理 + RabbitMQ异步下单 + 客户端轮询
        
        描述：通过三级缓冲保护
        1、本地标记
        2、redis预处理 
        3、RabbitMQ异步下单，最后才会访问数据库，这样做是为了最大力度减少对数据库的访问。

        实现：
        在秒杀阶段使用本地标记对用户秒杀过的商品做标记，若被标记过直接返回重复秒杀，未被标记才查询redis，
        通过本地标记来减少对redis的访问，抢购开始前，将商品和库存数据同步到redis中，所有的抢购操作都在redis中进行处理，
        通过Redis预减少库存减少数据库访问为了保护系统不受高流量的冲击而导致系统崩溃的问题，使用RabbitMQ用异步队列处理下单，
        实际做了一层缓冲保护，做了一个窗口模型，窗口模型会实时的刷新用户秒杀的状态。client端用js轮询一个接口，用来获取处理状态
        
8. 解决超卖
        
        描述：
        比如某商品的库存为1，此时用户1和用户2并发购买该商品，用户1提交订单后该商品的库存被修改为0
        而此时用户2并不知道的情况下提交订单，该商品的库存再次被修改为-1，这就是超卖现象

        实现：
        对库存更新时，先对库存判断，只有当库存大于0才能更新库存
        对用户id和商品id建立一个唯一索引，通过这种约束避免同一用户发同时两个请求秒杀到两件相同商品
        实现乐观锁，给商品信息表增加一个version字段，为每一条数据加上版本。每次更新的时候version+1，并且更新时候带上版本号
        当提交前版本号等于更新前版本号，说明此时没有被其他线程影响到，正常更新，如果冲突了则不会进行提交更新。
        当库存是足够的情况下发生乐观锁冲突就进行一定次数的重试。
        
9. 使用数学公式验证码
        
        描述：
        点击秒杀前，先让用户输入数学公式验证码，验证正确才能进行秒杀。
        
        好处：
        防止恶意的机器人和爬虫
        分散用户的请求
        
        实现：
        前端通过把商品id作为参数调用服务端创建验证码接口
        服务端根据前端传过来的商品id和用户id生成验证码，并将商品id+用户id作为key，生成的验证码作为value存入redis
        同时将生成的验证码输入图片写入imageIO让前端展示
        将用户输入的验证码与根据商品id+用户id从redis查询到的验证码对比，相同就返回验证成功，进入秒杀；
        不同或从redis查询的验证码为空都返回验证失败，刷新验证码重试
        
10. 使用AccessLimit实现限流
        
        描述：
        当我们去秒杀一些商品时，此时可能会因为访问量太大而导致系统崩溃，此时要使用限流来进行限制访问量，当达到限流阀值
        后续请求会被降级；降级后的处理方案可以是：返回排队页面（高峰期访问太频繁，等一会重试）、错误页等。
        
        实现：
        项目使用AccessLimit+Redis来实现限流，AccessLimit是一个注解，定义了时间、访问次数
        
### 部分代码预览：
            
mybatis接口：        
![](https://github.com/jxnu-liguobin/SpringBoot-SecKill-Scala/blob/master/SpringBoot-SecKill-Scala/src/main/resources/images/mybatis%E6%8E%A5%E5%8F%A3.png)
拦截器接口实现：
![](https://github.com/jxnu-liguobin/SpringBoot-SecKill-Scala/blob/master/SpringBoot-SecKill-Scala/src/main/resources/images/%E6%8B%A6%E6%88%AA%E5%99%A8.png)
Swagger
![](https://github.com/jxnu-liguobin/SpringBoot-SecKill-Scala/blob/master/SpringBoot-SecKill-Scala/src/main/resources/images/api.png)


### 写该项目理由有以下几点：

        1、学习新的编程范式，没有好的资源，所以重构已有项目
        2、熟悉Scala基本语法
        3、熟悉慕课网秒杀系统

### 代码需要注意的地方：

        1、刚刚写完，存在bug，没有进行压测，性能没比较，而且可能还有BUG，欢迎发邮件给我指出bug。
        2、基本没有使用复杂特性，会Java的学起来很容易。
        3、由于Scala注解与Java相差不小，或者说我还没有找到很好的兼容已有接口的办法，所以暂时注解部分由Java替代。
        4、本项目中不存在分号，return只保留部分歧义的地方，Java程序员要注意。
        5、本项目没有使用高级特性，如高阶函数、闭包，但使用了隐式转换、通配符、泛型，包括各种类、构造、特质、伴生对象、匿名函数等等。
        6、本项目集合一律使用Java集合，并指定别名为：JavaHashMap,JavaList类似取名。基本类型是混用的
        7、为了配合Java的set/get，采用了与Java兼容的写法，这个需要完善也可以，但是得去掉100%的Java代码
        8、实体类仅仅实现了toString方法，需要注意，没有其他！
        9、部分代码强行移植，缺乏观赏性，待优化。
        10、需要针对函数式编程进行优化
        11、对于多参数多个@ApiImplicitParam，暂时无能为力，Scala注解实在太坑了【这部分使用Swagger默认】
 


### 其他需要注意的地方：

        1、前台将与慕课网完全一致，可以忽略了。
        2、后台预计纯Scala实现，不排除无法实现或不方便实现的则使用Java，【实际99% Scala，仅是注解暂时没用】
        3、非Scala爱好者忽略本项目，本人不提供也没有时间答疑，也就是说你要自己搞，除非是项目BUG
        4、该Scala版本的版权归本人所有。
        5、遵循MIT开源
        
        
 
### 如何使用：

        1、使用就很暴力了，要想自动初始化数据库，就给mysql新建一个库，叫seckill
        2、把resources/sql下的schema.xml与data.xml放到resources下，启动主类即可
        3、想手动，就把那两个文件分别去mysql执行一遍吧
        4、IDEA貌似可以直接启动Scala，但是Eclipse必须以Scala Appliction启动，或者以SpringBoot方式启动。注意环境是否全部装好了
