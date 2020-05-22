package com.example.admin.mybledemo.utils;

import android.content.Context;
import android.util.Log;

import com.example.admin.mybledemo.C;
import com.example.admin.mybledemo.activity.BleActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Created by admin on 2017/1/16.
 */

public class FileUtils {

    /**
     * 拷贝OTA升级文件到SD卡
     */
    public static void copyAssets2SD(final Context context, final String path) {
        //判断是否是第一次进入   默认第一次进入
        if (!SPUtils.get(context, C.SP.IS_FIRST_RUN, true)) {
            return;
        }
        TaskExecutor.executeTask(new Runnable() {
            @Override
            public void run() {
                // 获取SD卡路径
                File file = new File(path);
                // 如果SD卡目录不存在创建
                if (!file.exists()) {
                    file.mkdir();
                }
                final File newFile = new File(path + C.Constance.OTA_NEW_PATH);
                final File oldFile = new File(path + C.Constance.OTA_OLD_PATH);
                try {
                    copyBigDataToSD(context, C.Constance.OTA_NEW_PATH, newFile.getAbsolutePath());
                    copyBigDataToSD(context, C.Constance.OTA_OLD_PATH, oldFile.getAbsolutePath());
                } catch (IOException e) {
                    e.printStackTrace();
                }
                //设置程序非第一次进入
                SPUtils.put(context, C.SP.IS_FIRST_RUN, false);
            }
        });
    }

    public static void copyBigDataToSD(Context context, String assetPath, String strOutFileName) throws IOException {
        InputStream myInput;
        OutputStream myOutput = new FileOutputStream(strOutFileName);
        myInput = context.getAssets().open(assetPath);
        byte[] buffer = new byte[1024];
        int length = myInput.read(buffer);
        while (length > 0) {
            myOutput.write(buffer, 0, length);
            length = myInput.read(buffer);
        }
        Log.e("FlieUtils==", "复制到sd卡完成");
        myOutput.flush();
        myInput.close();
        myOutput.close();
    }

    //删除文件夹下的所有文件
    public static boolean deleteDir(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                deleteDir(f);
            }
        }else if (file.exists()) {
            file.delete();
        }
        // 目录此时为空，可以删除
        return true;
    }

    //读取指定目录下的所有TXT文件的文件内容
    public static String getFileContent(File file) {
        String content = "";
        if (!file.isDirectory()) {  //检查此路径名的文件是否是一个目录(文件夹)
            if (file.getName().endsWith("txt")) {//文件格式为""文件
                try {
                    InputStream instream = new FileInputStream(file);
                    if (instream != null) {
                        InputStreamReader inputreader
                                = new InputStreamReader(instream, "UTF-8");
                        BufferedReader buffreader = new BufferedReader(inputreader);
                        String line = "";
                        //分行读取
                        while ((line = buffreader.readLine()) != null) {
                            content += line + "\n";
                        }
                        instream.close();//关闭输入流
                    }
                } catch (java.io.FileNotFoundException e) {
                    Log.d("TestFile", "The File doesn't not exist.");
                } catch (IOException e) {
                    Log.d("TestFile", e.getMessage());
                }
            }
        }
        return content;
    }
}
