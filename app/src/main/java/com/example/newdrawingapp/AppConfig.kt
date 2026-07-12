package com.example.newdrawingapp

/**
 * 应用配置类
 * 
 * 用于控制应用的各种功能开关
 */
object AppConfig {
    /**
     * 序列号验证开关
     * 
     * true  = 需要序列号验证（启动流程：SerialVerifyActivity → PasswordActivity → MainActivity/ClientActivity）
     * false = 不需要序列号验证（启动流程：PasswordActivity → MainActivity/ClientActivity）
     * 
     * 修改此值后重新编译即可切换版本
     */
    const val REQUIRE_SERIAL_VERIFICATION = false
}
