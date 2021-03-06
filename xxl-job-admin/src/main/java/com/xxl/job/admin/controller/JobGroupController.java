package com.xxl.job.admin.controller;

import com.xxl.job.admin.core.model.XxlJobGroup;
import com.xxl.job.admin.core.model.XxlJobRegistry;
import com.xxl.job.admin.core.util.I18nUtil;
import com.xxl.job.admin.dao.XxlJobGroupDao;
import com.xxl.job.admin.dao.XxlJobInfoDao;
import com.xxl.job.admin.dao.XxlJobRegistryDao;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.enums.RegistryConfig;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * job group controller
 *
 * @author xuxueli 2016-10-02 20:52:56
 */
@Controller
@RequestMapping("/jobgroup")
public class JobGroupController {

    @Resource
    private XxlJobInfoDao xxlJobInfoDao;
    @Resource
    private XxlJobGroupDao xxlJobGroupDao;
    @Resource
    private XxlJobRegistryDao xxlJobRegistryDao;

    @RequestMapping
    public String index(Model model) {
        return "jobgroup/jobgroup.index";
    }

    @RequestMapping("/pageList")
    @ResponseBody
    public Map<String, Object> pageList(HttpServletRequest request,
                                        @RequestParam(required = false, defaultValue = "0") int start,
                                        @RequestParam(required = false, defaultValue = "10") int length,
                                        String appName, String title) {

        // page query
        List<XxlJobGroup> list = xxlJobGroupDao.pageList(start, length, appName, title);
        int list_count = xxlJobGroupDao.pageListCount(appName, title);

        // package result
        Map<String, Object> maps = new HashMap<>();
        // 总记录数
        maps.put("recordsTotal", list_count);
        // 过滤后的总记录数
        maps.put("recordsFiltered", list_count);
        // 分页列表
        maps.put("data", list);
        return maps;
    }

    @RequestMapping("/save")
    @ResponseBody
    public ReturnT<String> save(XxlJobGroup xxlJobGroup) {

        // valid
        if (xxlJobGroup.getAppName() == null || xxlJobGroup.getAppName().trim().length() == 0) {
            return new ReturnT<>(500, (I18nUtil.getString("system_please_input") + "AppName"));
        }
        if (xxlJobGroup.getAppName().length() < 4 || xxlJobGroup.getAppName().length() > 64) {
            return new ReturnT<>(500, I18nUtil.getString("jobgroup_field_appName_length"));
        }
        if (xxlJobGroup.getTitle() == null || xxlJobGroup.getTitle().trim().length() == 0) {
            return new ReturnT<>(500, (I18nUtil.getString("system_please_input") + I18nUtil.getString("jobgroup_field_title")));
        }
        if (xxlJobGroup.getAddressType() != 0) {
            if (xxlJobGroup.getAddressList() == null || xxlJobGroup.getAddressList().trim().length() == 0) {
                return new ReturnT<>(500, I18nUtil.getString("jobgroup_field_addressType_limit"));
            }
            String[] addresses = xxlJobGroup.getAddressList().split(",");
            for (String item : addresses) {
                if (item == null || item.trim().length() == 0) {
                    return new ReturnT<>(500, I18nUtil.getString("jobgroup_field_registryList_unvalid"));
                }
            }
        }

        int ret = xxlJobGroupDao.save(xxlJobGroup);
        return (ret > 0) ? ReturnT.SUCCESS : ReturnT.FAIL;
    }

    @RequestMapping("/update")
    @ResponseBody
    public ReturnT<String> update(XxlJobGroup xxlJobGroup) {
        // valid
        if (xxlJobGroup.getAppName() == null || xxlJobGroup.getAppName().trim().length() == 0) {
            return new ReturnT<>(500, (I18nUtil.getString("system_please_input") + "AppName"));
        }
        if (xxlJobGroup.getAppName().length() < 4 || xxlJobGroup.getAppName().length() > 64) {
            return new ReturnT<>(500, I18nUtil.getString("jobgroup_field_appName_length"));
        }
        if (xxlJobGroup.getTitle() == null || xxlJobGroup.getTitle().trim().length() == 0) {
            return new ReturnT<>(500, (I18nUtil.getString("system_please_input") + I18nUtil.getString("jobgroup_field_title")));
        }
        // 自动注册
        if (xxlJobGroup.getAddressType() == 0) {
            List<String> registryList = findRegistryByAppName(xxlJobGroup.getAppName());
            String addressListStr = null;
            if (registryList != null && !registryList.isEmpty()) {
                Collections.sort(registryList);
                addressListStr = "";
                for (String item : registryList) {
                    addressListStr += item + ",";
                }
                addressListStr = addressListStr.substring(0, addressListStr.length() - 1);
            }
            xxlJobGroup.setAddressList(addressListStr);
        }
        // 手动录入
        else {
            if (xxlJobGroup.getAddressList() == null || xxlJobGroup.getAddressList().trim().length() == 0) {
                return new ReturnT<>(500, I18nUtil.getString("jobgroup_field_addressType_limit"));
            }
            String[] addresses = xxlJobGroup.getAddressList().split(",");
            for (String item : addresses) {
                if (item == null || item.trim().length() == 0) {
                    return new ReturnT<>(500, I18nUtil.getString("jobgroup_field_registryList_unvalid"));
                }
            }
        }

        int ret = xxlJobGroupDao.update(xxlJobGroup);
        return (ret > 0) ? ReturnT.SUCCESS : ReturnT.FAIL;
    }

    private List<String> findRegistryByAppName(String appNameParam) {
        HashMap<String, List<String>> appAddressMap = new HashMap<>();
        List<XxlJobRegistry> list = xxlJobRegistryDao.findAll(RegistryConfig.DEAD_TIMEOUT, new Date());
        if (list != null) {
            for (XxlJobRegistry item : list) {
                if (RegistryConfig.RegistrationType.EXECUTOR.name().equals(item.getRegistryGroup())) {
                    String appName = item.getRegistryKey();
                    List<String> registryList = appAddressMap.get(appName);
                    if (registryList == null) {
                        registryList = new ArrayList<>();
                    }

                    if (!registryList.contains(item.getRegistryValue())) {
                        registryList.add(item.getRegistryValue());
                    }
                    appAddressMap.put(appName, registryList);
                }
            }
        }
        return appAddressMap.get(appNameParam);
    }

    @RequestMapping("/remove")
    @ResponseBody
    public ReturnT<String> remove(int id) {
        // valid
        int count = xxlJobInfoDao.pageListCount(0, 10, id, -1, null, null, null);
        if (count > 0) {
            return new ReturnT<>(500, I18nUtil.getString("jobgroup_del_limit_0"));
        }

        List<XxlJobGroup> allList = xxlJobGroupDao.findAll();
        if (allList.size() == 1) {
            return new ReturnT<>(500, I18nUtil.getString("jobgroup_del_limit_1"));
        }

        int ret = xxlJobGroupDao.remove(id);
        return (ret > 0) ? ReturnT.SUCCESS : ReturnT.FAIL;
    }

    @RequestMapping("/loadById")
    @ResponseBody
    public ReturnT<XxlJobGroup> loadById(int id) {
        XxlJobGroup jobGroup = xxlJobGroupDao.load(id);
        return jobGroup != null ? new ReturnT<>(jobGroup) : new ReturnT<>(ReturnT.FAIL_CODE, null);
    }

}
