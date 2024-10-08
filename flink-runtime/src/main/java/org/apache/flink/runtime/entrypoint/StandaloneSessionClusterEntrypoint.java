/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.entrypoint;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.entrypoint.component.DefaultDispatcherResourceManagerComponentFactory;
import org.apache.flink.runtime.entrypoint.parser.CommandLineParser;
import org.apache.flink.runtime.resourcemanager.StandaloneResourceManagerFactory;
import org.apache.flink.runtime.util.EnvironmentInformation;
import org.apache.flink.runtime.util.JvmShutdownSafeguard;
import org.apache.flink.runtime.util.SignalHandler;

/** Entry point for the standalone session cluster. */
public class StandaloneSessionClusterEntrypoint extends SessionClusterEntrypoint {

    public StandaloneSessionClusterEntrypoint(Configuration configuration) {
        super(configuration);
    }

    @Override
    protected DefaultDispatcherResourceManagerComponentFactory
            createDispatcherResourceManagerComponentFactory(Configuration configuration) {
        // clouding 注释: 2022/1/22 17:52
        //          组件工厂
        return DefaultDispatcherResourceManagerComponentFactory.createSessionComponentFactory(
                StandaloneResourceManagerFactory.getInstance());
    }

    /*********************
     * clouding 注释: 2021/9/6 15:28
     *   启动程序入口
     *********************/
    public static void main(String[] args) {
        // startup checks and logging
        // clouding 注释: 2022/1/22 17:16
        //          打印环境信息,启动的版本信息
        EnvironmentInformation.logEnvironmentInfo(
                LOG, StandaloneSessionClusterEntrypoint.class.getSimpleName(), args);
        // clouding 注释: 2022/1/22 17:16
        //          注册一些信号处理程序
        //          HUP 中断信号,让程序挂起,睡眠, INT 中断信号 是正常关闭所有进程
        SignalHandler.register(LOG);
        // clouding 注释: 2022/1/22 17:19
        //          注册关闭的钩子
        JvmShutdownSafeguard.installAsShutdownHook(LOG);

        EntrypointClusterConfiguration entrypointClusterConfiguration = null;
        final CommandLineParser<EntrypointClusterConfiguration> commandLineParser =
                new CommandLineParser<>(new EntrypointClusterConfigurationParserFactory());

        try {
            /*********************
            * clouding 注释: 2022/1/22 17:19
            *  	     解析启动的参数
            *********************/
            entrypointClusterConfiguration = commandLineParser.parse(args);
        } catch (FlinkParseException e) {
            LOG.error("Could not parse command line arguments {}.", args, e);
            commandLineParser.printHelp(StandaloneSessionClusterEntrypoint.class.getSimpleName());
            System.exit(1);
        }

        // clouding 注释: 2022/1/22 17:25
        //          解析 flink-conf.ymal 配置文件
        Configuration configuration = loadConfiguration(entrypointClusterConfiguration);

        StandaloneSessionClusterEntrypoint entrypoint =
                new StandaloneSessionClusterEntrypoint(configuration);

        /*********************
        * clouding 注释: 2022/1/22 17:36
        *  	       启动
        *********************/
        ClusterEntrypoint.runClusterEntrypoint(entrypoint);
    }
}
