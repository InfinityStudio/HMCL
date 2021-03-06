/*
 * Hello Minecraft! Launcher.
 * Copyright (C) 2013  huangyuhui <huanghongxun2008@126.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see {http://www.gnu.org/licenses/}.
 */
package org.jackhuang.hellominecraft.launcher.core.asset;

import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import org.jackhuang.hellominecraft.launcher.core.GameException;
import org.jackhuang.hellominecraft.launcher.core.launch.IAssetProvider;
import org.jackhuang.hellominecraft.util.C;
import org.jackhuang.hellominecraft.launcher.core.service.IMinecraftAssetService;
import org.jackhuang.hellominecraft.launcher.core.service.IMinecraftService;
import org.jackhuang.hellominecraft.launcher.core.version.AssetIndexDownloadInfo;
import org.jackhuang.hellominecraft.launcher.core.version.MinecraftVersion;
import org.jackhuang.hellominecraft.util.MessageBox;
import org.jackhuang.hellominecraft.util.log.HMCLog;
import org.jackhuang.hellominecraft.util.task.Task;
import org.jackhuang.hellominecraft.util.task.TaskWindow;
import org.jackhuang.hellominecraft.util.net.FileDownloadTask;
import org.jackhuang.hellominecraft.util.sys.FileUtils;
import org.jackhuang.hellominecraft.util.sys.IOUtils;
import org.jackhuang.hellominecraft.util.task.TaskInfo;

/**
 *
 * @author huangyuhui
 */
public class MinecraftAssetService extends IMinecraftAssetService {

    public MinecraftAssetService(IMinecraftService service) {
        super(service);
    }

    @Override
    public Task downloadAssets(final String mcVersion) throws GameException {
        return downloadAssets(service.version().getVersionById(mcVersion));
    }

    public Task downloadAssets(final MinecraftVersion mv) throws GameException {
        if (mv == null)
            return null;
        return IAssetsHandler.ASSETS_HANDLER.getList(mv.resolve(service.version()), service.asset()).with(IAssetsHandler.ASSETS_HANDLER.getDownloadTask(service.getDownloadType().getProvider()));
    }

    @Override
    public boolean refreshAssetsIndex(String id) throws GameException {
        MinecraftVersion mv = service.version().getVersionById(id);
        if (mv == null)
            return false;
        return downloadMinecraftAssetsIndexAsync(mv.resolve(service.version()).getAssetsIndex());
    }

    @Override
    public Task downloadMinecraftAssetsIndex(AssetIndexDownloadInfo assetIndex) {
        File assetsLocation = getAssets();
        if (!FileUtils.makeDirectory(assetsLocation))
            HMCLog.warn("Failed to make directories: " + assetsLocation);
        File assetsIndex = getIndexFile(assetIndex.getId());
        File renamed = null;
        if (assetsIndex.exists()) {
            renamed = new File(assetsLocation, "indexes/" + assetIndex.getId() + "-renamed.json");
            if (assetsIndex.renameTo(renamed))
                HMCLog.warn("Failed to rename " + assetsIndex + " to " + renamed);
        }
        File renamedFinal = renamed;
        return new TaskInfo("Download Asset Index") {
            @Override
            public Collection<Task> getDependTasks() {
                return Arrays.asList(new FileDownloadTask(assetIndex.getUrl(service.getDownloadType()), IOUtils.tryGetCanonicalFile(assetsIndex), assetIndex.sha1).setTag(assetIndex.getId() + ".json"));
            }

            @Override
            public void executeTask(boolean areDependTasksSucceeded) throws Throwable {
                if (areDependTasksSucceeded) {
                    if (renamedFinal != null && !renamedFinal.delete())
                        HMCLog.warn("Failed to delete " + renamedFinal + ", maybe you should do it.");
                } else if (renamedFinal != null && !renamedFinal.renameTo(assetsIndex))
                    HMCLog.warn("Failed to rename " + renamedFinal + " to " + assetsIndex);
            }
        };
    }

    @Override
    public boolean downloadMinecraftAssetsIndexAsync(AssetIndexDownloadInfo assetIndex) {
        File assetsDir = getAssets();
        if (!FileUtils.makeDirectory(assetsDir))
            HMCLog.warn("Failed to make directories: " + assetsDir);
        File assetsIndex = getIndexFile(assetIndex.getId());
        File renamed = null;
        if (assetsIndex.exists()) {
            renamed = new File(assetsDir, "indexes/" + assetIndex.getId() + "-renamed.json");
            if (assetsIndex.renameTo(renamed))
                HMCLog.warn("Failed to rename " + assetsIndex + " to " + renamed);
        }
        if (TaskWindow.factory()
            .append(new FileDownloadTask(assetIndex.getUrl(service.getDownloadType()), IOUtils.tryGetCanonicalFile(assetsIndex), assetIndex.sha1).setTag(assetIndex.getId() + ".json"))
            .execute()) {
            if (renamed != null && !renamed.delete())
                HMCLog.warn("Failed to delete " + renamed + ", maybe you should do it.");
            return true;
        }
        if (renamed != null && !renamed.renameTo(assetsIndex))
            HMCLog.warn("Failed to rename " + renamed + " to " + assetsIndex);
        return false;
    }

    @Override
    public File getAssets() {
        return new File(service.baseDirectory(), "assets");
    }
    
    private File getIndexFile(String assetVersion) {
        return new File(getAssets(), "indexes/" + assetVersion + ".json");
    }

    @Override
    public File getAssetObject(String assetVersion, String name) throws IOException {
        try {
            AssetsIndex index = (AssetsIndex) C.GSON.fromJson(FileUtils.read(getIndexFile(assetVersion), "UTF-8"), AssetsIndex.class);

            String hash = ((AssetsObject) index.getFileMap().get(name)).getHash();
            return new File(getAssets(), "objects/" + hash.substring(0, 2) + "/" + hash);
        } catch (JsonSyntaxException e) {
            throw new IOException("Assets file format malformed.", e);
        }
    }

    private boolean checkAssetsExistance(AssetIndexDownloadInfo assetIndex) {
        File indexFile = getIndexFile(assetIndex.getId());

        if (!getAssets().exists() || !indexFile.isFile())
            return false;

        try {
            String assetIndexContent = FileUtils.read(indexFile, "UTF-8");
            AssetsIndex index = (AssetsIndex) C.GSON.fromJson(assetIndexContent, AssetsIndex.class);

            if (index == null)
                return false;
            for (Map.Entry<String, AssetsObject> entry : index.getFileMap().entrySet())
                if (!new File(getAssets(), "objects/" + ((AssetsObject) entry.getValue()).getHash().substring(0, 2) + "/" + ((AssetsObject) entry.getValue()).getHash()).exists())
                    return false;
            return true;
        } catch (IOException | JsonSyntaxException e) {
            return false;
        }
    }

    private File reconstructAssets(AssetIndexDownloadInfo assetIndex) {
        File assetsDir = getAssets();
        String assetVersion = assetIndex.getId();
        File indexFile = getIndexFile(assetVersion);
        File virtualRoot = new File(new File(assetsDir, "virtual"), assetVersion);

        if (!indexFile.isFile()) {
            HMCLog.warn("No assets index file " + virtualRoot + "; can't reconstruct assets");
            return assetsDir;
        }

        try {
            String assetIndexContent = FileUtils.read(indexFile, "UTF-8");
            AssetsIndex index = (AssetsIndex) C.GSON.fromJson(assetIndexContent, AssetsIndex.class);

            if (index == null)
                return assetsDir;
            if (index.isVirtual()) {
                int cnt = 0;
                HMCLog.log("Reconstructing virtual assets folder at " + virtualRoot);
                int tot = index.getFileMap().entrySet().size();
                for (Map.Entry<String, AssetsObject> entry : index.getFileMap().entrySet()) {
                    File target = new File(virtualRoot, (String) entry.getKey());
                    File original = new File(assetsDir, "objects/" + ((AssetsObject) entry.getValue()).getHash().substring(0, 2) + "/" + ((AssetsObject) entry.getValue()).getHash());
                    if (original.exists()) {
                        cnt++;
                        if (!target.isFile())
                            FileUtils.copyFile(original, target);
                    }
                }
                // If the scale new format existent file is lower then 0.1, use the old format.
                if (cnt * 10 < tot)
                    return assetsDir;
            }
        } catch (IOException | JsonSyntaxException e) {
            HMCLog.warn("Failed to create virutal assets.", e);
        }

        return virtualRoot;
    }

    public final IAssetProvider ASSET_PROVIDER_IMPL = (t, allow) -> {
        if (allow && !checkAssetsExistance(t.getAssetsIndex()))
            if (MessageBox.show(C.i18n("assets.no_assets"), MessageBox.YES_NO_OPTION) == MessageBox.YES_OPTION)
                TaskWindow.factory().execute(downloadAssets(t));
        return reconstructAssets(t.getAssetsIndex()).getAbsolutePath();
    };
}
