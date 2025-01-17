/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef AMR_EXTRACTOR_H_

#define AMR_EXTRACTOR_H_

#include <media/stagefright/MediaExtractor.h>
#include <utils/List.h>
namespace android {

class String8;
struct AMRFrameTableEntry {

   AMRFrameTableEntry(uint32_t numframes, uint32_t framesize, uint32_t framerate)
      : mNumFrames(numframes),
      mFrameSize(framesize),
      mFrameRate(framerate) {}

   uint64_t mNumFrames;
   uint32_t mFrameSize;
   uint32_t mFrameRate;
};

class AMRExtractor : public MediaExtractor {
public:
    AMRExtractor(const sp<DataSource> &source);

    virtual size_t countTracks();
    virtual sp<MediaSource> getTrack(size_t index);
    virtual sp<MetaData> getTrackMetaData(size_t index, uint32_t flags);

    virtual sp<MetaData> getMetaData();

protected:
    virtual ~AMRExtractor();

private:
    sp<DataSource> mDataSource;
    sp<MetaData> mMeta;
    status_t mInitCheck;
    size_t mFrameSize;
    bool mIsWide;
    uint64_t mTotalFrames;
    List<AMRFrameTableEntry> mAMRFrameTableEntries;
    AMRExtractor(const AMRExtractor &);
    AMRExtractor &operator=(const AMRExtractor &);
};

bool SniffAMR(
        const sp<DataSource> &source, String8 *mimeType, float *confidence);

}  // namespace android

#endif  // AMR_EXTRACTOR_H_
