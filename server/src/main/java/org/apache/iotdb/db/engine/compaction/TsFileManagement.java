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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.engine.compaction;

import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.cache.ChunkCache;
import org.apache.iotdb.db.engine.cache.TimeSeriesMetadataCache;
import org.apache.iotdb.db.engine.merge.manage.MergeResource;
import org.apache.iotdb.db.engine.merge.selector.IMergeFileSelector;
import org.apache.iotdb.db.engine.merge.selector.MaxFileMergeFileSelector;
import org.apache.iotdb.db.engine.merge.selector.MaxSeriesMergeFileSelector;
import org.apache.iotdb.db.engine.merge.selector.MergeFileStrategy;
import org.apache.iotdb.db.engine.merge.task.MergeTask;
import org.apache.iotdb.db.engine.modification.Modification;
import org.apache.iotdb.db.engine.modification.ModificationFile;
import org.apache.iotdb.db.engine.storagegroup.StorageGroupProcessor.CloseCompactionMergeCallBack;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.tsfile.fileSystem.FSFactoryProducer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.apache.iotdb.db.conf.IoTDBConstant.FILE_NAME_SEPARATOR;
import static org.apache.iotdb.db.engine.merge.task.MergeTask.MERGE_SUFFIX;
import static org.apache.iotdb.db.engine.storagegroup.StorageGroupProcessor.MERGING_MODIFICATION_FILE_NAME;
import static org.apache.iotdb.tsfile.common.constant.TsFileConstant.TSFILE_SUFFIX;

public abstract class TsFileManagement {

  private static final Logger logger = LoggerFactory.getLogger(TsFileManagement.class);
  protected String storageGroupName;
  protected String storageGroupDir;

  /** Serialize queries, delete resource files, compaction cleanup files */
  private final ReadWriteLock compactionMergeLock = new ReentrantReadWriteLock();

  /**
   * This is the modification file of the result of the current merge. Because the merged file may
   * be invisible at this moment, without this, deletion/update during merge could be lost.
   */
  public ModificationFile mergingModification;

  /** whether execute merge chunk in this task */
  protected boolean isMergeExecutedInCurrentTask = false;

  protected boolean isForceFullMerge = IoTDBDescriptor.getInstance().getConfig().isFullMerge();
  private final int maxOpenFileNumInEachUnseqCompaction =
      IoTDBDescriptor.getInstance().getConfig().getMaxOpenFileNumInEachUnseqCompaction();

  public TsFileManagement(String storageGroupName, String storageGroupDir) {
    this.storageGroupName = storageGroupName;
    this.storageGroupDir = storageGroupDir;
  }

  public void setFullMerge(boolean forceFullMerge) {
    isForceFullMerge = forceFullMerge;
  }

  /**
   * get the TsFile list in sequence, not recommend to use this method, use
   * getTsFileListByTimePartition instead
   */
  @Deprecated
  public abstract List<TsFileResource> getTsFileList(boolean sequence);

  /** get the TsFile list in sequence by time partition */
  public abstract List<TsFileResource> getTsFileListByTimePartition(
      boolean sequence, long timePartition);

  /** get the TsFile list iterator in sequence */
  public abstract Iterator<TsFileResource> getIterator(boolean sequence);

  /** remove one TsFile from list */
  public abstract void remove(TsFileResource tsFileResource, boolean sequence);

  /** remove some TsFiles from list */
  public abstract void removeAll(List<TsFileResource> tsFileResourceList, boolean sequence);

  /** add one TsFile to list */
  public abstract void add(TsFileResource tsFileResource, boolean sequence);

  /** add one TsFile to list for recover */
  public abstract void addRecover(TsFileResource tsFileResource, boolean sequence);

  /** add some TsFiles to list */
  public abstract void addAll(List<TsFileResource> tsFileResourceList, boolean sequence);

  /** is one TsFile contained in list */
  public abstract boolean contains(TsFileResource tsFileResource, boolean sequence);

  /** clear list */
  public abstract void clear();

  /** is the list empty */
  public abstract boolean isEmpty(boolean sequence);

  /** return TsFile list size */
  public abstract int size(boolean sequence);

  /** recover TsFile list */
  public abstract void recover();

  /** fork current TsFile list (call this before merge) */
  public abstract void forkCurrentFileList(long timePartition) throws IOException;

  protected void readLock() {
    compactionMergeLock.readLock().lock();
  }

  protected void readUnLock() {
    compactionMergeLock.readLock().unlock();
  }

  public void writeLock() {
    compactionMergeLock.writeLock().lock();
  }

  public void writeUnlock() {
    compactionMergeLock.writeLock().unlock();
  }

  public boolean tryWriteLock() {
    return compactionMergeLock.writeLock().tryLock();
  }

  protected abstract void merge(long timePartition);

  public class CompactionMergeTask implements Callable<Void> {

    private CloseCompactionMergeCallBack closeCompactionMergeCallBack;
    private long timePartitionId;

    public CompactionMergeTask(
        CloseCompactionMergeCallBack closeCompactionMergeCallBack, long timePartitionId) {
      this.closeCompactionMergeCallBack = closeCompactionMergeCallBack;
      this.timePartitionId = timePartitionId;
    }

    @Override
    public Void call() {
      merge(timePartitionId);
      closeCompactionMergeCallBack.call(isMergeExecutedInCurrentTask, timePartitionId);
      return null;
    }
  }

  public class LevelCompactionRecoverTask implements Callable<Void> {

    private CloseCompactionMergeCallBack closeCompactionMergeCallBack;

    public LevelCompactionRecoverTask(CloseCompactionMergeCallBack closeCompactionMergeCallBack) {
      this.closeCompactionMergeCallBack = closeCompactionMergeCallBack;
    }

    @Override
    public Void call() {
      recover();
      // in recover logic, we do not have to start next compaction task, and in this case the param
      // time partition is useless, we can just pass 0L
      closeCompactionMergeCallBack.call(false, 0L);
      return null;
    }
  }

  protected void doUnseqMerge(
      boolean fullMerge,
      List<TsFileResource> seqMergeList,
      List<TsFileResource> unSeqMergeList) {

    if (seqMergeList.isEmpty() || unSeqMergeList.isEmpty()) {
      logger.info("{} no files to be merged, seqFiles={}, unseqFiles={}",
          storageGroupName, seqMergeList.size(), unSeqMergeList.size());
      return;
    }

    // the number of unseq files in one merge should not exceed maxOpenFileNumInEachUnseqCompaction
    if (unSeqMergeList.size() > maxOpenFileNumInEachUnseqCompaction) {
      unSeqMergeList = unSeqMergeList.subList(0, maxOpenFileNumInEachUnseqCompaction);
    }

    long timeLowerBound = System.currentTimeMillis() - IoTDBDescriptor.getInstance().getConfig().getDefaultTTL();
    MergeResource mergeResource = new MergeResource(seqMergeList, unSeqMergeList, timeLowerBound);

    IMergeFileSelector fileSelector = getMergeFileSelector(mergeResource);
    try {
      List[] mergeFiles = fileSelector.select();
      if (mergeFiles.length == 0) {
        logger.info(
            "{} cannot select merge candidates under the budget {}",
            storageGroupName, IoTDBDescriptor.getInstance().getConfig().getMergeMemoryBudget());
        return;
      }

      mergeResource.startMerging();

      MergeTask mergeTask =
          new MergeTask(
              mergeResource,
              storageGroupDir,
              this::mergeEndAction,
              fullMerge,
              fileSelector.getConcurrentMergeNum(),
              storageGroupName);

      mergingModification =
          new ModificationFile(storageGroupDir + File.separator + MERGING_MODIFICATION_FILE_NAME);

      logger.info(
          "{} start merge {} seqFiles, {} unseqFiles", storageGroupName,
          mergeFiles[0].size(),
          mergeFiles[1].size());

      mergeTask.doMerge();

    } catch (Exception e) {
      logger.error("{} cannot select file for merge", storageGroupName, e);
    }
  }

  private IMergeFileSelector getMergeFileSelector(MergeResource resource) {
    long budget = IoTDBDescriptor.getInstance().getConfig().getMergeMemoryBudget();
    MergeFileStrategy strategy = IoTDBDescriptor.getInstance().getConfig().getMergeFileStrategy();
    switch (strategy) {
      case MAX_FILE_NUM:
        return new MaxFileMergeFileSelector(resource, budget);
      case MAX_SERIES_NUM:
        return new MaxSeriesMergeFileSelector(resource, budget);
      default:
        throw new UnsupportedOperationException("Unknown MergeFileStrategy " + strategy);
    }
  }

  /** acquire the write locks of the resource , the merge lock and the compaction lock */
  private void doubleWriteLock(TsFileResource seqFile) {
    boolean fileLockGot;
    boolean compactionLockGot;
    while (true) {
      fileLockGot = seqFile.tryWriteLock();
      compactionLockGot = tryWriteLock();

      if (fileLockGot && compactionLockGot) {
        break;
      } else {
        // did not get all of them, release the gotten one and retry
        if (compactionLockGot) {
          writeUnlock();
        }
        if (fileLockGot) {
          seqFile.writeUnlock();
        }
      }
    }
  }

  /** release the write locks of the resource , the merge lock and the compaction lock */
  private void doubleWriteUnlock(TsFileResource seqFile) {
    writeUnlock();
    seqFile.writeUnlock();
  }

  private void removeUnseqFiles(List<TsFileResource> unseqFiles) {
    writeLock();
    try {
      removeAll(unseqFiles, false);
      // clean cache
      if (IoTDBDescriptor.getInstance().getConfig().isMetaDataCacheEnable()) {
        ChunkCache.getInstance().clear();
        TimeSeriesMetadataCache.getInstance().clear();
      }
    } finally {
      writeUnlock();
    }

    for (TsFileResource unseqFile : unseqFiles) {
      unseqFile.writeLock();
      try {
        unseqFile.remove();
      } finally {
        unseqFile.writeUnlock();
      }
    }
  }

  @SuppressWarnings("squid:S1141")
  private void updateMergeModification(TsFileResource seqFile) {
    try {
      // remove old modifications and write modifications generated during merge
      seqFile.removeModFile();
      if (mergingModification != null) {
        for (Modification modification : mergingModification.getModifications()) {
          // we have to set modification offset to MAX_VALUE, as the offset of source chunk may
          // change after compaction
          modification.setFileOffset(Long.MAX_VALUE);
          seqFile.getModFile().write(modification);
        }
        try {
          seqFile.getModFile().close();
        } catch (IOException e) {
          logger.error(
              "Cannot close the ModificationFile {}", seqFile.getModFile().getFilePath(), e);
        }
      }
    } catch (IOException e) {
      logger.error(
          "{} cannot clean the ModificationFile of {} after merge",
          storageGroupName,
          seqFile.getTsFile(),
          e);
    }
  }

  private void removeMergingModification() {
    try {
      if (mergingModification != null) {
        mergingModification.remove();
        mergingModification = null;
      }
    } catch (IOException e) {
      logger.error("{} cannot remove merging modification ", storageGroupName, e);
    }
  }

  public void mergeEndAction(
      List<TsFileResource> seqFiles, List<TsFileResource> unseqFiles, File mergeLog) {
    logger.info("{} a merge task is ending...", storageGroupName);

    if (Thread.currentThread().isInterrupted() || unseqFiles.isEmpty()) {
      // merge task abort, or merge runtime exception arose, just end this merge
      logger.info("{} a merge task abnormally ends", storageGroupName);
      return;
    }
    removeUnseqFiles(unseqFiles);

    for (TsFileResource seqFile : seqFiles) {
      // get both seqFile lock and merge lock
      doubleWriteLock(seqFile);

      try {
        // if meet error(like file not found) in merge task, the .merge file may not be deleted
        File mergedFile =
            FSFactoryProducer.getFSFactory().getFile(seqFile.getTsFilePath() + MERGE_SUFFIX);
        if (mergedFile.exists()) {
          if (!mergedFile.delete()) {
            logger.warn("Delete file {} failed", mergedFile);
          }
        }
        updateMergeModification(seqFile);
      } finally {
        doubleWriteUnlock(seqFile);
      }
    }

    try {
      removeMergingModification();
      Files.delete(mergeLog.toPath());
    } catch (IOException e) {
      logger.error(
          "{} a merge task ends but cannot delete log {}", storageGroupName, mergeLog.toPath());
    }

    logger.info("{} a merge task ends", storageGroupName);
  }

  // ({systemTime}-{versionNum}-{mergeNum}.tsfile)
  public static int compareFileName(File o1, File o2) {
    String[] items1 = o1.getName().replace(TSFILE_SUFFIX, "").split(FILE_NAME_SEPARATOR);
    String[] items2 = o2.getName().replace(TSFILE_SUFFIX, "").split(FILE_NAME_SEPARATOR);
    long ver1 = Long.parseLong(items1[0]);
    long ver2 = Long.parseLong(items2[0]);
    int cmp = Long.compare(ver1, ver2);
    if (cmp == 0) {
      int cmpVersion = Long.compare(Long.parseLong(items1[1]), Long.parseLong(items2[1]));
      if (cmpVersion == 0) {
        return Long.compare(Long.parseLong(items1[2]), Long.parseLong(items2[2]));
      }
      return cmpVersion;
    } else {
      return cmp;
    }
  }
}
