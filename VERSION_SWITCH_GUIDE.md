# 版本切换指南

## 如何切换是否需要序列号验证

### 步骤：

1. 打开文件：`app/src/main/java/com/example/newdrawingapp/AppConfig.kt`

2. 找到这一行：
   ```kotlin
   const val REQUIRE_SERIAL_VERIFICATION = false
   ```

3. 修改值：
   - **需要序列号验证** → 改为 `true`
   - **不需要序列号验证** → 改为 `false`

4. 重新编译运行应用

## 版本区别

### REQUIRE_SERIAL_VERIFICATION = true（需要序列号）
- **启动流程**: SplashActivity → SerialVerifyActivity → PasswordActivity → MainActivity/ClientActivity
- **特性**: 
  - 首次启动需要输入序列号
  - 需要联网验证序列号
  - 验证通过后保存状态，下次直接进入密码页面

### REQUIRE_SERIAL_VERIFICATION = false（不需要序列号）
- **启动流程**: SplashActivity → PasswordActivity → MainActivity/ClientActivity
- **特性**: 
  - 直接进入密码页面
  - 无需联网验证
  - 跳过序列号验证环节

## 示例

```kotlin
// app/src/main/java/com/example/newdrawingapp/AppConfig.kt

object AppConfig {
    // 改为 true 表示需要序列号验证
    const val REQUIRE_SERIAL_VERIFICATION = true
    
    // 改为 false 表示不需要序列号验证
    // const val REQUIRE_SERIAL_VERIFICATION = false
}
```

## 注意事项

1. 修改后需要重新编译（Clean Project + Rebuild Project）
2. 如果设备上已安装旧版本，建议卸载后重新安装
3. 序列号验证状态保存在 SharedPreferences 中，卸载应用会清除
