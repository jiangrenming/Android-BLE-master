package com.example.admin.mybledemo.annotation;

import android.view.View;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created by LiuLei on 2017/11/29.
 */

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@EventInterface(lisenterSetter="setOnClickListener",lisenterType=View.OnClickListener.class,callbackMethod="onClick")
public @interface OnClick {
    // 由于有很多个点击事件，所以要搞个数组
    int[] value();
}
