/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.image;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.direct.download.DirectDownloadManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.image.store.TemplateObject;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class TemplateDataFactoryImpl implements TemplateDataFactory {
    private static final Logger s_logger = Logger.getLogger(TemplateDataFactoryImpl.class);
    @Inject
    VMTemplateDao imageDataDao;
    @Inject
    DataStoreManager storeMgr;
    @Inject
    VMTemplatePoolDao templatePoolDao;
    @Inject
    TemplateDataStoreDao templateStoreDao;
    @Inject
    DirectDownloadManager directDownloadManager;
    @Inject
    HostDao hostDao;
    @Inject
    PrimaryDataStoreDao primaryDataStoreDao;

    @Override
    public TemplateInfo getTemplate(long templateId, DataStore store) {
        VMTemplateVO templ = imageDataDao.findById(templateId);
        if (store == null && !templ.isDirectDownload()) {
            TemplateObject tmpl = TemplateObject.getTemplate(templ, null);
            return tmpl;
        }
        // verify if the given input parameters are consistent with our db data.
        boolean found = false;
        if (store.getRole() == DataStoreRole.Primary) {
            VMTemplateStoragePoolVO templatePoolVO = templatePoolDao.findByPoolTemplate(store.getId(), templateId);
            if (templatePoolVO != null) {
                found = true;
            }
        } else {
            TemplateDataStoreVO templateStoreVO = templateStoreDao.findByStoreTemplate(store.getId(), templateId);
            if (templateStoreVO != null) {
                found = true;
            }
        }

        if (s_logger.isDebugEnabled()) {
            if (!found) {
                s_logger.debug("template " + templateId + " is not in store:" + store.getId() + ", type:" + store.getRole());
            } else {
                s_logger.debug("template " + templateId + " is already in store:" + store.getId() + ", type:" + store.getRole());
            }
        }

        TemplateObject tmpl = TemplateObject.getTemplate(templ, store);
        return tmpl;
    }

    @Override
    public TemplateInfo getTemplate(long templateId, DataStoreRole storeRole) {
        TemplateDataStoreVO tmplStore = templateStoreDao.findByTemplate(templateId, storeRole);
        DataStore store = null;
        if (tmplStore != null) {
            store = storeMgr.getDataStore(tmplStore.getDataStoreId(), storeRole);
        }
        return this.getTemplate(templateId, store);
    }

    @Override
    public TemplateInfo getTemplate(long templateId, DataStoreRole storeRole, Long zoneId) {
        TemplateDataStoreVO tmplStore = templateStoreDao.findByTemplateZone(templateId, zoneId, storeRole);
        DataStore store = null;
        if (tmplStore != null) {
            store = storeMgr.getDataStore(tmplStore.getDataStoreId(), storeRole);
        }
        return this.getTemplate(templateId, store);
    }

    @Override
    public TemplateInfo getReadyTemplateOnImageStore(long templateId, Long zoneId) {
        TemplateDataStoreVO tmplStore = templateStoreDao.findByTemplateZoneReady(templateId, zoneId);
        if (tmplStore != null) {
            DataStore store = storeMgr.getDataStore(tmplStore.getDataStoreId(), DataStoreRole.Image);
            return this.getTemplate(templateId, store);
        } else {
            return null;
        }
    }

    @Override
    public TemplateInfo getTemplate(DataObject obj, DataStore store) {
        TemplateObject tmpObj = (TemplateObject)this.getTemplate(obj.getId(), store);
        // carry over url set in passed in data object, for copyTemplate case
        // where url is generated on demand and not persisted in DB.
        // need to think of a more generic way to pass these runtime information
        // carried through DataObject post 4.2
        TemplateObject origTmpl = (TemplateObject)obj;
        tmpObj.setUrl(origTmpl.getUrl());
        return tmpObj;
    }

    @Override
    public TemplateInfo getReadyTemplateOnCache(long templateId) {
        TemplateDataStoreVO tmplStore = templateStoreDao.findReadyOnCache(templateId);
        if (tmplStore != null) {
            DataStore store = storeMgr.getDataStore(tmplStore.getDataStoreId(), DataStoreRole.ImageCache);
            return getTemplate(templateId, store);
        } else {
            return null;
        }
    }

    @Override
    public List<TemplateInfo> listTemplateOnCache(long templateId) {
        List<TemplateDataStoreVO> cacheTmpls = templateStoreDao.listOnCache(templateId);
        List<TemplateInfo> tmplObjs = new ArrayList<TemplateInfo>();
        for (TemplateDataStoreVO cacheTmpl : cacheTmpls) {
            long storeId = cacheTmpl.getDataStoreId();
            DataStore store = storeMgr.getDataStore(storeId, DataStoreRole.ImageCache);
            TemplateInfo tmplObj = getTemplate(templateId, store);
            tmplObjs.add(tmplObj);
        }
        return tmplObjs;
    }

    /**
     * Given existing spool refs, return one pool id existing on pools and refs
     */
    private Long getOneMatchingPoolIdFromRefs(List<VMTemplateStoragePoolVO> existingRefs, List<StoragePoolVO> pools) {
        if (pools.isEmpty()) {
            throw new CloudRuntimeException("No storage pools found");
        }
        if (existingRefs.isEmpty()) {
            return pools.get(0).getId();
        } else {
            for (VMTemplateStoragePoolVO ref : existingRefs) {
                for (StoragePoolVO p : pools) {
                    if (ref.getPoolId() == p.getId()) {
                        return p.getId();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Retrieve storage pools with scope = cluster or zone matching clusterId or dataCenterId depending on their scope
     */
    private List<StoragePoolVO> getStoragePoolsFromClusterOrZone(Long clusterId, long dataCenterId, Hypervisor.HypervisorType hypervisorType) {
        List<StoragePoolVO> pools = new ArrayList<>();
        if (clusterId != null) {
            List<StoragePoolVO> clusterPools = primaryDataStoreDao.listPoolsByCluster(clusterId);
            pools.addAll(clusterPools);
        }
        List<StoragePoolVO> zonePools = primaryDataStoreDao.findZoneWideStoragePoolsByHypervisor(dataCenterId, hypervisorType);
        pools.addAll(zonePools);
        return pools;
    }

    @Override
    public TemplateInfo getReadyBypassedTemplateOnPrimaryStore(long templateId, Long poolId, Long hostId) {
        VMTemplateVO templateVO = imageDataDao.findById(templateId);
        if (templateVO == null || !templateVO.isDirectDownload()) {
            return null;
        }
        Long pool = poolId;
        if (poolId == null) {
            //Get ISO from existing pool ref
            HostVO host = hostDao.findById(hostId);
            List<StoragePoolVO> pools = getStoragePoolsFromClusterOrZone(host.getClusterId(), host.getDataCenterId(), host.getHypervisorType());
            List<VMTemplateStoragePoolVO> existingRefs = templatePoolDao.listByTemplateId(templateId);
            pool = getOneMatchingPoolIdFromRefs(existingRefs, pools);
        }
        if (pool == null) {
            throw new CloudRuntimeException("No storage pool found where to download template: " + templateId);
        }
        VMTemplateStoragePoolVO spoolRef = templatePoolDao.findByPoolTemplate(pool, templateId);
        if (spoolRef == null) {
            directDownloadManager.downloadTemplate(templateId, pool, hostId);
        }
        DataStore store = storeMgr.getDataStore(pool, DataStoreRole.Primary);
        return this.getTemplate(templateId, store);
    }

    @Override
    public boolean isTemplateMarkedForDirectDownload(long templateId) {
        VMTemplateVO templateVO = imageDataDao.findById(templateId);
        return templateVO.isDirectDownload();
    }
}
