
/**
 * Tencent is pleased to support the open source community by making MSEC available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the GNU General Public License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. You may 
 * obtain a copy of the License at
 *
 *     https://opensource.org/licenses/GPL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the 
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */


package beans.service;

import beans.dbaccess.LibraryFile;
import beans.dbaccess.SecondLevelServiceConfigTag;
import beans.dbaccess.SharedobjectTag;
import beans.request.ReleasePlan;
import ngse.org.*;
import org.apache.log4j.Logger;

import javax.servlet.ServletContext;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Administrator on 2016/2/16.
 * 打包一个tar文件，里面包含要发布的完整信息
 */
public class PackReleaseFile implements Runnable {
    ReleasePlan plan;
    ServletContext servletContext;//用来读取war包里的spp.tar
    public PackReleaseFile(ReleasePlan p, ServletContext context)
    {
        servletContext = context;
        plan = p;
    }
    private ArrayList<LibraryFile> getLibraryFilesFromDB(String flsn, String slsn)
    {
        Logger logger = Logger.getLogger(PackReleaseFile.class);

        DBUtil util = new DBUtil();
        if (util.getConnection() == null)
        {
            return null;
        }

        try {

            String sql = "select file_name from t_library_file where first_level_service_name=? and second_level_service_name=?";
            List<Object> params = new ArrayList<Object>();
            params.add(flsn);
            params.add(slsn);



            ArrayList<LibraryFile> ret = util.findMoreRefResult(sql, params, LibraryFile.class);
            logger.info("get library file list:"+ret.size());
            return ret;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            logger.error(e.getMessage());
            return null;
        }
        finally {
            util.releaseConn();
        }
    }
    private boolean copyFile(File frm, File to)
    {
        try {
            FileInputStream in = new FileInputStream(frm);
            FileOutputStream out = new FileOutputStream(to);
            byte[] buf = new byte[1024*8];
            while (true)
            {
                int len = in.read(buf);
                if (len <= 0)
                {
                    break;
                }
                out.write(buf, 0, len);
            }
            in.close();
            out.close();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

    }
    private void updateProcessInfo(String plan_id, String info)
    {
        Logger logger = Logger.getLogger(PackReleaseFile.class);
        DBUtil util = new DBUtil();
        if (util.getConnection() == null) {
            return;
        }

        try {

            String sql = "update t_release_plan set backend_task_status = ? where plan_id=?";
            List<Object> params = new ArrayList<Object>();
            params.add(info);
            params.add(plan_id);

            logger.info("update progress status:"+info);


            int updNum = util.updateByPreparedStatement(sql, params);
            if (updNum != 1) {
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.error(e.getMessage());
            return;
        } finally {
            util.releaseConn();
        }
    }

    private String copyCnfFile(String baseDir)
    {
        Logger logger = Logger.getLogger(PackReleaseFile.class);

        String destFile = baseDir+File.separator+"etc"+File.separator+"config.ini";
        String srcFile = SecondLevelServiceConfigTag.getConfigFileName(plan.getFirst_level_service_name(),
                plan.getSecond_level_service_name(), plan.getConfig_tag());
        if (copyFile(new File(srcFile), new File(destFile)))
        {
            logger.info("copy file successfully:"+srcFile+" "+destFile);
            return "success";
        }
        else
        {
            logger.error("failed to copy file:"+srcFile+" "+destFile);
            return "failed";
        }

    }
    private String copyLibraryFile(String baseDir)
    {
        Logger logger = Logger.getLogger(PackReleaseFile.class);
        ArrayList<LibraryFile> libraryFiles = getLibraryFilesFromDB(plan.getFirst_level_service_name(), plan.getSecond_level_service_name());
        if (libraryFiles == null) {return "getLibraryFilesFromDB() failed";}

        for (int i = 0; i < libraryFiles.size(); i++) {
            String destFile = baseDir+"/bin/lib/"+libraryFiles.get(i).getFile_name();
            String srcFile = LibraryFile.getLibraryFileName(plan.getFirst_level_service_name(),
                    plan.getSecond_level_service_name(), libraryFiles.get(i).getFile_name());
            if (copyFile(new File(srcFile), new File(destFile)))
            {
                logger.info("copy file successfully:"+srcFile+" "+destFile);
                continue;
            }
            else
            {
                logger.error("failed to copy file:"+srcFile+" "+destFile);
                return "failed";
            }
        }
        return "success";
    }
    private String copySharedobject(String baseDir, String dev_lang)
    {
        Logger logger = Logger.getLogger(PackReleaseFile.class);
        String suffix = "so";//不管什么语言，一开始都是用.so文件先存着的
        String destFile = baseDir+File.separator+"bin"+File.separator+"msec.so";
        if (dev_lang.equals("java"))
        {
            destFile = baseDir+File.separator+"bin"+File.separator+"msec.jar";
        }
        String srcFile = SharedobjectTag.getSharedobjectName(plan.getFirst_level_service_name(),
                plan.getSecond_level_service_name(), plan.getSharedobject_tag(), suffix);
        if (copyFile(new File(srcFile), new File(destFile)))
        {
            logger.info("copy file successfully:"+srcFile+" "+destFile);
            return "success";
        }
        else
        {
            logger.error("failed to copy file:"+srcFile+" "+destFile);
            return "failed";
        }
    }

    private void switchPlanStatus(String plan_id, boolean success)
    {
        Logger logger = Logger.getLogger(PackReleaseFile.class);
        DBUtil util = new DBUtil();
        if (util.getConnection() == null)
        {
            return;
        }
        String status = "created successfully";
        if (!success)
        {
            status = "failed to create";
        }

        try {

            String sql = "update t_release_plan set status = ? where plan_id=?";
            List<Object> params = new ArrayList<Object>();
            params.add(status);
            params.add(plan_id);

            logger.info("update plan status:"+status);

            int updNum = util.updateByPreparedStatement(sql, params);
            if (updNum != 1) {
                return;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            logger.error(e.getMessage());
            return;
        }
        finally {
            util.releaseConn();
        }
    }

    private String mktar(String baseDir, String tarFileName)
    {
        Logger logger = Logger.getLogger(PackReleaseFile.class);
        try
        {
            TarUtil.archive(baseDir, tarFileName);
            logger.info("make tar successfully." + tarFileName);
        }
        catch (Exception e)
        {
            logger.error(e.getMessage());
            return e.getMessage();
        }

        return "success";
    }


    private String getSppFromResource(String plan_id, String baseDir)
    {
        Logger logger = Logger.getLogger(PackReleaseFile.class);
        //将war包资源中的spp.tar解压到目录baseDir
        InputStream inputStream = servletContext.getResourceAsStream("/resource/spp.tar");
        String tmpTarFile = ServletConfig.fileServerRootDir + File.separator+"tmp"+File.separator+plan_id+".tar";

        File f = null;

        logger.info("copy spp in resource to "+tmpTarFile);

        //将资源文件拷贝到文件系统中的普通文件，并解压缩为目录baseDir
        try {
            OutputStream outputStream = new FileOutputStream(tmpTarFile);
            byte[] buf = new byte[10240];
            int len;
            while (true) {


                len = inputStream.read(buf);

                if (len <= 0) {
                    break;
                }
                outputStream.write(buf, 0, len);

            }
            outputStream.close();
            inputStream.close();


            //把普通文件解包到目录baseDir
            TarUtil.dearchive(new File(tmpTarFile), new File(baseDir));
            logger.info("dearchive file successfully. dir=" + baseDir);

            //删除普通文件
            new File(tmpTarFile).delete();
        } catch (Exception e) {
            e.printStackTrace();
            logger.error(e.getMessage());
            return e.getMessage();
        }
        return "success";


    }
    private String getJavaFrameworkFromResource(String plan_id, String baseDir)
    {
        Logger logger = Logger.getLogger(PackReleaseFile.class);
        //将war包资源中的spp.tar解压到目录baseDir
        InputStream inputStream = servletContext.getResourceAsStream("/resource/java.tar");
        String tmpTarFile = ServletConfig.fileServerRootDir + File.separator+"tmp"+File.separator+plan_id+".tar";

        File f = null;

        logger.info("copy java.tar in resource to "+tmpTarFile);

        //将资源文件拷贝到文件系统中的普通文件，并解压缩为目录baseDir
        try {
            OutputStream outputStream = new FileOutputStream(tmpTarFile);
            byte[] buf = new byte[10240];
            int len;
            while (true) {


                len = inputStream.read(buf);

                if (len <= 0) {
                    break;
                }
                outputStream.write(buf, 0, len);

            }
            outputStream.close();
            inputStream.close();


            //把普通文件解包到目录baseDir
            TarUtil.dearchive(new File(tmpTarFile), new File(baseDir));
            logger.info("dearchive file successfully. dir="+baseDir);

            //删除普通文件
            new File(tmpTarFile).delete();
        } catch (Exception e) {
            e.printStackTrace();
            logger.error(e.getMessage());
            return e.getMessage();
        }
        return "success";


    }

    public static String getPackedFile(String plan_id)
    {
        return ServletConfig.fileServerRootDir+ "/tmp/"+plan_id+".tar.gz";
    }

    @Override
    public void run() {
        int i;
        Logger logger = Logger.getLogger(PackReleaseFile.class);

        String baseDir = ServletConfig.fileServerRootDir+ File.separator+"tmp"+File.separator+plan.getPlan_id();
        String tarFileName = baseDir +".tar";
        String result;


        if (plan.getDev_lang().equals("c++")) {
            result = getSppFromResource(plan.getPlan_id(), baseDir);
        }
        else
        {
            result = getJavaFrameworkFromResource(plan.getPlan_id(), baseDir);
        }
        if (!result.equals("success"))
        {
            updateProcessInfo(plan.getPlan_id(), result);
            switchPlanStatus(plan.getPlan_id(), false);
            return;
        }
        updateProcessInfo(plan.getPlan_id(), "成功拷贝spp.tar...");




        try {

            if (plan.getRelease_type().equals("only_config")) {//只发布配置文件
                result = copyCnfFile(baseDir);
                if (!result.equals("success")) {
                    updateProcessInfo(plan.getPlan_id(), result);
                    switchPlanStatus(plan.getPlan_id(), false);
                    return;
                }
                updateProcessInfo(plan.getPlan_id(), "成功拷贝配置文件...");
            }
            else if (plan.getRelease_type().equals("only_library"))//只发布外部库
            {
                result = copyLibraryFile(baseDir);
                if (!result.equals("success")) {
                    updateProcessInfo(plan.getPlan_id(), result);
                    switchPlanStatus(plan.getPlan_id(), false);
                    return;
                }
                updateProcessInfo(plan.getPlan_id(), "成功拷贝库文件...");
            }
            else if (plan.getRelease_type().equals("only_sharedobject"))//只发布业务插件
            {
                result = copySharedobject(baseDir, plan.getDev_lang());
                if (!result.equals("success")) {
                    updateProcessInfo(plan.getPlan_id(), result);
                    switchPlanStatus(plan.getPlan_id(), false);
                    return;
                }
                updateProcessInfo(plan.getPlan_id(), "成功拷贝业务逻辑代码...");
            }
            else//完整的发布
            {
                result = copyCnfFile(baseDir);
                if (!result.equals("success")) {
                    updateProcessInfo(plan.getPlan_id(), result);
                    switchPlanStatus(plan.getPlan_id(), false);
                    return;
                }
                updateProcessInfo(plan.getPlan_id(), "成功拷贝配置文件...");



                result = copyLibraryFile(baseDir);
                if (!result.equals("success")) {
                    updateProcessInfo(plan.getPlan_id(), result);
                    switchPlanStatus(plan.getPlan_id(), false);
                    return;
                }
                updateProcessInfo(plan.getPlan_id(), "成功拷贝库文件...");

                result = copySharedobject(baseDir, plan.getDev_lang());
                if (!result.equals("success")) {
                    updateProcessInfo(plan.getPlan_id(), result);
                    switchPlanStatus(plan.getPlan_id(), false);
                    return;
                }
                updateProcessInfo(plan.getPlan_id(), "成功拷贝业务逻辑代码...");

                if (plan.getDev_lang().equals("java")) {
                    //执行python脚本
                    String[] cmd = new String[2];
                    String pythonScript;
                    String outputDir;

                    pythonScript = baseDir + "/create_rpc_release.py";
                    new File(pythonScript).setExecutable(true);

                    cmd[0] = pythonScript;
                    cmd[1] = "bin/msec.jar";

                    StringBuffer cmdResult = new StringBuffer();
                    if (Tools.runCommand(cmd, cmdResult, true) != 0)
                    {
                        logger.error(cmdResult.toString());
                        updateProcessInfo(plan.getPlan_id(), "执行python脚本失败:"+cmdResult);
                        switchPlanStatus(plan.getPlan_id(), false);
                        return;
                    }
                    logger.info("run python script successfully.");

                }

            }


            result = mktar(baseDir, tarFileName);
            if (!result.equals("success")) {
                updateProcessInfo(plan.getPlan_id(), result);
                switchPlanStatus(plan.getPlan_id(), false);
                return;
            }
            GzipUtil.zip(tarFileName);
            updateProcessInfo(plan.getPlan_id(), "tar文件归档成功");
            switchPlanStatus(plan.getPlan_id(), true);

            //删除临时目录
            Tools.deleteDirectory(new File(baseDir));

        }
        catch (Exception e)
        {
            e.printStackTrace();
            logger.error(e.getMessage());
        }
        finally {
            new File(baseDir).delete();
        }









    }
}
