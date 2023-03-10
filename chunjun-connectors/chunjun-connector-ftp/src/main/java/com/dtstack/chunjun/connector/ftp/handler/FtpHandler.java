/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.chunjun.connector.ftp.handler;

import com.dtstack.chunjun.connector.ftp.config.FtpConfig;
import com.dtstack.chunjun.connector.ftp.enums.EFtpMode;
import com.dtstack.chunjun.constants.ConstantValue;
import com.dtstack.chunjun.throwable.ChunJunRuntimeException;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class FtpHandler implements DTFtpHandler {

    private static final String SP = "/";
    private FTPClient ftpClient = null;
    private String controlEncoding;
    private FtpConfig ftpConfig;

    public FTPClient getFtpClient() {
        return ftpClient;
    }

    @Override
    public void loginFtpServer(FtpConfig ftpConfig) {
        this.ftpConfig = ftpConfig;
        controlEncoding = ftpConfig.getControlEncoding();
        ftpClient = new FTPClient();
        try {
            // ??????
            ftpClient.connect(ftpConfig.getHost(), ftpConfig.getPort());
            // ??????
            ftpClient.login(ftpConfig.getUsername(), ftpConfig.getPassword());
            // ???????????????ftp server???OS TYPE,FTPClient getSystemType()?????????????????????
            ftpClient.setConnectTimeout(ftpConfig.getTimeout());
            ftpClient.setDataTimeout(ftpConfig.getTimeout());
            // ????????????????????????
            ftpClient.setSoTimeout(ftpConfig.getTimeout());
            if (EFtpMode.PASV.name().equals(ftpConfig.getConnectPattern())) {
                ftpClient.enterRemotePassiveMode();
                ftpClient.enterLocalPassiveMode();
            } else if (EFtpMode.PORT.name().equals(ftpConfig.getConnectPattern())) {
                ftpClient.enterLocalActiveMode();
            }
            int reply = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftpClient.disconnect();
                String message =
                        String.format(
                                "???ftp???????????????????????????,???????????????????????????????????????: [%s]",
                                "message:host ="
                                        + ftpConfig.getHost()
                                        + ",username = "
                                        + ftpConfig.getUsername()
                                        + ",port ="
                                        + ftpConfig.getPort());
                log.error(message);
                throw new RuntimeException(message);
            }
            ftpClient.setControlEncoding(ftpConfig.getControlEncoding());
            ftpClient.setListHiddenFiles(ftpConfig.isListHiddenFiles());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void logoutFtpServer() throws IOException {
        if (ftpClient.isConnected()) {
            try {
                ftpClient.logout();
            } finally {
                if (ftpClient.isConnected()) {
                    ftpClient.disconnect();
                }
            }
        }
    }

    @Override
    public boolean isDirExist(String directoryPath) {
        String originDir = null;
        try {
            originDir = ftpClient.printWorkingDirectory();
            ftpClient.enterLocalPassiveMode();
            FTPFile[] ftpFiles = ftpClient.listFiles(encodePath(directoryPath));
            if (ftpFiles.length == 0
                    && !ftpClient.changeWorkingDirectory(encodePath(directoryPath))) {
                return false;
            }
            boolean positiveCompletion =
                    FTPReply.isPositiveCompletion(ftpClient.cwd(encodePath(directoryPath)));
            if (positiveCompletion && ftpFiles.length == 1 && ftpFiles[0].isFile()) {
                String[] split = directoryPath.split(String.valueOf(IOUtils.DIR_SEPARATOR_UNIX));
                // ?????????????????????????????? ??????????????????????????????
                if (ftpFiles[0].getName().equals(split[split.length - 1])) {
                    String ftpFilePath =
                            directoryPath + IOUtils.DIR_SEPARATOR_UNIX + ftpFiles[0].getName();
                    return ftpClient.listFiles(encodePath(ftpFilePath)).length != 0;
                }
            }
            return positiveCompletion;

        } catch (IOException e) {
            String message = String.format("???????????????[%s]?????????I/O??????,????????????ftp????????????????????????", directoryPath);
            log.error(message);
            throw new RuntimeException(message, e);
        } finally {
            if (originDir != null) {
                try {
                    ftpClient.changeWorkingDirectory(originDir);
                } catch (IOException e) {
                    log.error(e.getMessage());
                }
            }
        }
    }

    @Override
    public boolean isFileExist(String filePath) throws IOException {
        ftpClient.enterLocalPassiveMode();
        try (InputStream inputStream = ftpClient.retrieveFileStream(encodePath(filePath))) {
            return inputStream != null && ftpClient.getReplyCode() != 550;
        } catch (IOException e) {
            throw new ChunJunRuntimeException(
                    "An exception occurred when judging whether the file exists. filepath: "
                            + filePath);
        } finally {
            ftpClient.completePendingCommand();
        }
    }

    @Override
    public List<String> getFiles(String path) {
        List<String> sources = new ArrayList<>();
        ftpClient.enterLocalPassiveMode();

        if (!isExist(path)) {
            return sources;
        }
        try {
            if (isFileExist(path)) {
                sources.add(path);
                return sources;
            } else {
                path = path + SP;
            }
            FTPFile[] ftpFiles = ftpClient.listFiles(encodePath(path));
            if (ftpFiles != null) {
                for (FTPFile ftpFile : ftpFiles) {
                    // .???..???????????????
                    if (StringUtils.endsWith(ftpFile.getName(), ConstantValue.POINT_SYMBOL)
                            || StringUtils.endsWith(
                                    ftpFile.getName(), ConstantValue.TWO_POINT_SYMBOL)) {
                        continue;
                    }
                    sources.addAll(getFiles(path + ftpFile.getName(), ftpFile));
                }
            }
        } catch (IOException e) {
            log.error("", e);
            throw new RuntimeException(e);
        }
        return sources;
    }

    /**
     * ??????????????????????????????????????????(????????????)
     *
     * @param path ????????????
     * @param file FTP file
     * @return ????????????
     * @throws IOException io exception
     */
    private List<String> getFiles(String path, FTPFile file) throws IOException {
        List<String> sources = new ArrayList<>();
        if (file.isDirectory()) {
            if (!path.endsWith(SP)) {
                path = path + SP;
            }
            ftpClient.enterLocalPassiveMode();
            FTPFile[] ftpFiles = ftpClient.listFiles(encodePath(path));
            if (ftpFiles != null) {
                for (FTPFile ftpFile : ftpFiles) {
                    if (StringUtils.endsWith(ftpFile.getName(), ConstantValue.POINT_SYMBOL)
                            || StringUtils.endsWith(
                                    ftpFile.getName(), ConstantValue.TWO_POINT_SYMBOL)) {
                        continue;
                    }
                    sources.addAll(getFiles(path + ftpFile.getName(), ftpFile));
                }
            }
        } else {
            sources.add(path);
        }
        log.info("path = {}, FTPFile is directory = {}", path, file.isDirectory());
        return sources;
    }

    @Override
    public void mkDirRecursive(String directoryPath) {
        StringBuilder dirPath = new StringBuilder();
        dirPath.append(IOUtils.DIR_SEPARATOR_UNIX);
        String[] dirSplit = StringUtils.split(directoryPath, IOUtils.DIR_SEPARATOR_UNIX);
        String message = String.format("????????????:%s???????????????,????????????ftp????????????????????????,????????????????????????", directoryPath);
        try {
            // ftp server???????????????????????????,????????????????????????
            for (String dirName : dirSplit) {
                dirPath.append(dirName);
                boolean mkdirSuccess = mkDirSingleHierarchy(dirPath.toString());
                dirPath.append(IOUtils.DIR_SEPARATOR_UNIX);
                if (!mkdirSuccess) {
                    throw new RuntimeException(message);
                }
            }
        } catch (IOException e) {
            message = String.format("%s, errorMessage:%s", message, e.getMessage());
            log.error(message);
            throw new RuntimeException(message, e);
        }
    }

    private boolean mkDirSingleHierarchy(String directoryPath) throws IOException {
        boolean isDirExist = this.ftpClient.changeWorkingDirectory(encodePath(directoryPath));
        // ??????directoryPath???????????????,?????????
        if (!isDirExist) {
            int replayCode = this.ftpClient.mkd(encodePath(directoryPath));
            if (replayCode != FTPReply.COMMAND_OK
                    && replayCode != FTPReply.PATHNAME_CREATED
                    && replayCode != FTPReply.FILE_ACTION_OK) {
                log.warn(
                        "create path [{}] failed ,replayCode is {} and reply is  {} ",
                        directoryPath,
                        replayCode,
                        ftpClient.getReplyString());
                return false;
            }
        }
        return true;
    }

    @Override
    public OutputStream getOutputStream(String filePath) {
        try {
            this.printWorkingDirectory();
            String parentDir =
                    filePath.substring(
                            0, StringUtils.lastIndexOf(filePath, IOUtils.DIR_SEPARATOR_UNIX));
            this.ftpClient.changeWorkingDirectory(parentDir);
            this.printWorkingDirectory();
            OutputStream writeOutputStream = this.ftpClient.appendFileStream(encodePath(filePath));
            String message =
                    String.format("??????FTP??????[%s]????????????????????????,???????????????%s????????????????????????????????????", filePath, filePath);
            if (null == writeOutputStream) {
                throw new RuntimeException(message);
            }

            return writeOutputStream;
        } catch (IOException e) {
            String message =
                    String.format(
                            "???????????? : [%s] ?????????,???????????????:[%s]????????????????????????????????????, errorMessage:%s",
                            filePath, filePath, e.getMessage());
            log.error(message);
            throw new RuntimeException(message, e);
        }
    }

    private void printWorkingDirectory() {
        try {
            log.info(
                    String.format(
                            "current working directory:%s",
                            this.ftpClient.printWorkingDirectory()));
        } catch (Exception e) {
            log.warn(String.format("printWorkingDirectory error:%s", e.getMessage()));
        }
    }

    @Override
    public void deleteAllFilesInDir(String dir, List<String> exclude) throws IOException {
        if (isDirExist(dir)) {
            if (!dir.endsWith(SP)) {
                dir = dir + SP;
            }

            try {
                FTPFile[] ftpFiles = ftpClient.listFiles(encodePath(dir));
                if (ftpFiles != null) {
                    for (FTPFile ftpFile : ftpFiles) {
                        if (CollectionUtils.isNotEmpty(exclude)
                                && exclude.contains(ftpFile.getName())) {
                            continue;
                        }
                        if (StringUtils.endsWith(ftpFile.getName(), ConstantValue.POINT_SYMBOL)
                                || StringUtils.endsWith(
                                        ftpFile.getName(), ConstantValue.TWO_POINT_SYMBOL)) {
                            continue;
                        }
                        deleteAllFilesInDir(dir + ftpFile.getName(), exclude);
                    }
                }

                if (CollectionUtils.isEmpty(exclude)) {
                    ftpClient.rmd(encodePath(dir));
                }
            } catch (IOException e) {
                log.error("", e);
                throw new RuntimeException(e);
            }
        } else if (isFileExist(dir)) {
            try {
                ftpClient.deleteFile(encodePath(dir));
            } catch (IOException e) {
                log.error("", e);
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void deleteFile(String filePath) throws IOException {
        try {
            if (isFileExist(filePath)) {
                ftpClient.deleteFile(filePath);
            }
        } catch (IOException e) {
            throw new IOException(e);
        }
    }

    @Override
    public InputStream getInputStream(String filePath) {
        try {
            ftpClient.enterLocalPassiveMode();
            return ftpClient.retrieveFileStream(encodePath(filePath));
        } catch (IOException e) {
            String message =
                    String.format("???????????? : [%s] ?????????,??????????????????[%s]???????????????????????????????????????", filePath, filePath);
            log.error(message);
            throw new RuntimeException(message, e);
        }
    }

    @Override
    public InputStream getInputStreamByPosition(String filePath, long startPosition) {
        if (startPosition != 0) {
            throw new RuntimeException("ftp????????????????????????????????????????????????");
        }

        return getInputStream(filePath);
    }

    @Override
    public void rename(String oldPath, String newPath) throws IOException {
        /* ??????windows, ????????????????????????, rename????????? */
        if (this.isFileExist(newPath)) {
            log.info(String.format("[%s] exist, delete it before rename", newPath));
            this.deleteFile(newPath);
        }

        ftpClient.rename(encodePath(oldPath), encodePath(newPath));
    }

    @Override
    public long getFileSize(String path) throws IOException {
        reconnectFtp();
        FTPFile[] ftpFiles = ftpClient.listFiles(path);
        if (ftpFiles == null || ftpFiles.length == 0) {
            throw new IOException("file does not exist path: " + path);
        }
        return ftpFiles[0].getSize();
    }

    /**
     * ????????????????????????
     *
     * @param path ???????????????
     * @return true ?????? false ?????????
     */
    private boolean isExist(String path) {
        String originDir = null;
        try {
            originDir = ftpClient.printWorkingDirectory();
            ftpClient.enterLocalPassiveMode();
            FTPFile[] ftpFiles = ftpClient.listFiles(encodePath(path));
            // ???????????? ?????? ????????????????????? length??????0 ??????changeWorkingDirectory???true?????????????????????
            return ftpFiles.length != 0 || ftpClient.changeWorkingDirectory(encodePath(path));
        } catch (IOException e) {
            String message = String.format("?????????[%s]?????????????????????I/O??????,????????????ftp????????????????????????", path);
            throw new RuntimeException(message, e);
        } finally {
            if (originDir != null) {
                try {
                    ftpClient.changeWorkingDirectory(originDir);
                } catch (IOException e) {
                    log.error(e.getMessage());
                }
            }
        }
    }

    private String encodePath(String path) throws UnsupportedEncodingException {
        return new String(path.getBytes(controlEncoding), FTP.DEFAULT_CONTROL_ENCODING);
    }

    public void reconnectFtp() {
        try {
            ftpClient.disconnect();
            loginFtpServer(ftpConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        this.logoutFtpServer();
    }
}
