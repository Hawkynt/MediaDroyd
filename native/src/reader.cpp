// PaddleOCR's PP-OCRv5 on ncnn: text found in a grey picture and read (#493). Converted from ncnn's own example
// (examples/ppocrv5.cpp, BSD-3-Clause, Tencent; app/THIRD-PARTY-NOTICE.ncnn.txt) — the detector's input and its
// probability map's threshold, box score and enlargement, the recogniser's 48-pixel lines and greedy CTC — without
// OpenCV: credits run across the picture, so a text region's box is upright, found as a connected region of the map.
// The models are PaddleOCR's PP-OCRv5_mobile_det and latin_PP-OCRv5_mobile_rec (Apache-2.0,
// app/THIRD-PARTY-NOTICE.PaddleOCR.txt), converted with paddle2onnx and pnnx as the example's notes say.
#include <algorithm>
#include <cmath>
#include <utility>
#include <vector>

#include "networks.h"

namespace mediadroyd {

namespace {

// The detector's input: at most this long a side, padded to a multiple of its stride with the example's grey.
constexpr int kDetectSide = 960;
constexpr int kStride = 32;
constexpr float kPad = 114.f;
// A pixel of the map is text above this; a region is kept whose mean is at least the box score.
constexpr float kBinary = 0.3f;
constexpr float kBoxScore = 0.6f;
// How much a region is enlarged to cover its letters: its height by this much, its width by as much of its height.
constexpr float kEnlarge = 1.95f;
// The recogniser reads lines this high, at most this wide.
constexpr int kLineHeight = 48;
constexpr int kLineWidth = 1280;

struct Region {
    int left;
    int top;
    int right;
    int bottom;
    float score;
};

// The connected regions of the map's text pixels (eight neighbours), each with its mean probability.
std::vector<Region> regions(const ncnn::Mat& map) {
    const int w = map.w;
    const int h = map.h;
    const float* p = map;
    std::vector<unsigned char> seen(static_cast<size_t>(w) * h, 0);
    std::vector<int> queue;
    std::vector<Region> found;
    for (int start = 0; start < w * h; start++) {
        if (seen[start] || p[start] <= kBinary) continue;
        Region r{start % w, start / w, start % w, start / w, 0.f};
        double sum = 0;
        int count = 0;
        queue.clear();
        queue.push_back(start);
        seen[start] = 1;
        while (!queue.empty()) {
            const int at = queue.back();
            queue.pop_back();
            const int x = at % w;
            const int y = at / w;
            sum += p[at];
            count++;
            r.left = std::min(r.left, x);
            r.right = std::max(r.right, x);
            r.top = std::min(r.top, y);
            r.bottom = std::max(r.bottom, y);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    const int nx = x + dx;
                    const int ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    const int next = ny * w + nx;
                    if (seen[next] || p[next] <= kBinary) continue;
                    seen[next] = 1;
                    queue.push_back(next);
                }
            }
        }
        r.score = static_cast<float>(sum / count);
        found.push_back(r);
    }
    return found;
}

class PpOcr : public TextReader {
public:
    ncnn::Net detector;
    ncnn::Net recogniser;

    std::vector<TextLine> read(const unsigned char* grey, int w, int h) override {
        std::vector<TextLine> lines;
        if (grey == nullptr || w <= 0 || h <= 0) return lines;
        float scale = 1.f;
        int sw = w;
        int sh = h;
        if (std::max(w, h) > kDetectSide) {
            scale = static_cast<float>(kDetectSide) / std::max(w, h);
            sw = static_cast<int>(w * scale);
            sh = static_cast<int>(h * scale);
        }
        ncnn::Mat in = ncnn::Mat::from_pixels_resize(grey, ncnn::Mat::PIXEL_GRAY2BGR, w, h, sw, sh);
        const int wpad = (sw + kStride - 1) / kStride * kStride - sw;
        const int hpad = (sh + kStride - 1) / kStride * kStride - sh;
        ncnn::Mat padded;
        ncnn::copy_make_border(in, padded, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2,
                               ncnn::BORDER_CONSTANT, kPad);
        const float mean[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
        const float norm[3] = {1 / 0.229f / 255.f, 1 / 0.224f / 255.f, 1 / 0.225f / 255.f};
        padded.substract_mean_normalize(mean, norm);
        ncnn::Mat map;
        {
            ncnn::Extractor ex = detector.create_extractor();
            ex.input("in0", padded);
            if (ex.extract("out0", map) != 0 || map.empty()) return lines;
        }
        for (const Region& r : regions(map)) {
            const float rw = static_cast<float>(r.right - r.left + 1);
            const float rh = static_cast<float>(r.bottom - r.top + 1);
            if (r.score < kBoxScore || std::max(rw, rh) < 3 * scale || rh > rw * 2.7f) continue;
            // Enlarged about its centre, then back to the picture's own pixels.
            const float cx = (r.left + rw / 2 - wpad / 2) / scale;
            const float cy = (r.top + rh / 2 - hpad / 2) / scale;
            const float bw = (rw + rh * (kEnlarge - 1)) / scale;
            const float bh = rh * kEnlarge / scale;
            const int x0 = std::max(0, static_cast<int>(std::lround(cx - bw / 2)));
            const int y0 = std::max(0, static_cast<int>(std::lround(cy - bh / 2)));
            const int x1 = std::min(w, static_cast<int>(std::lround(cx + bw / 2)));
            const int y1 = std::min(h, static_cast<int>(std::lround(cy + bh / 2)));
            if (x1 - x0 < 2 || y1 - y0 < 2) continue;
            TextLine line;
            line.x = x0;
            line.y = y0;
            line.width = x1 - x0;
            line.height = y1 - y0;
            line.ids = recognise(grey, w, h, line);
            if (!line.ids.empty()) lines.push_back(std::move(line));
        }
        std::sort(lines.begin(), lines.end(), [](const TextLine& a, const TextLine& b) {
            const int rowA = a.y + a.height / 2;
            const int rowB = b.y + b.height / 2;
            if (std::abs(rowA - rowB) > std::min(a.height, b.height) / 2) return rowA < rowB;
            return a.x < b.x;
        });
        return lines;
    }

private:
    // The ids of the line in [box] of [grey]: the crop at kLineHeight, through the recogniser, greedy CTC.
    std::vector<int> recognise(const unsigned char* grey, int w, int h, const TextLine& box) {
        std::vector<int> ids;
        const int width = std::clamp(box.width * kLineHeight / std::max(1, box.height), 8, kLineWidth);
        ncnn::Mat in = ncnn::Mat::from_pixels_roi_resize(grey, ncnn::Mat::PIXEL_GRAY2BGR, w, h, box.x, box.y,
                                                         box.width, box.height, width, kLineHeight);
        const float mean[3] = {127.5f, 127.5f, 127.5f};
        const float norm[3] = {1 / 127.5f, 1 / 127.5f, 1 / 127.5f};
        in.substract_mean_normalize(mean, norm);
        ncnn::Mat out;
        ncnn::Extractor ex = recogniser.create_extractor();
        ex.input("in0", in);
        if (ex.extract("out0", out) != 0 || out.empty()) return ids;
        int last = 0;
        for (int t = 0; t < out.h; t++) {
            const float* row = out.row(t);
            int best = 0;
            for (int c = 1; c < out.w; c++) {
                if (row[c] > row[best]) best = c;
            }
            // CTC: a repeat is one character, the blank (0) none.
            if (best != last && best > 0) ids.push_back(best);
            last = best;
        }
        return ids;
    }
};

}  // namespace

std::unique_ptr<TextReader> openPpOcr(const Loader& load, bool gpu) {
    auto reader = std::make_unique<PpOcr>();
    const bool vulkan = gpu && gpuReady();
    configure(reader->detector, vulkan);
    configure(reader->recogniser, vulkan);
    if (load(reader->detector, "det") != 0 || load(reader->recogniser, "rec") != 0) return nullptr;
    return reader;
}

}  // namespace mediadroyd
