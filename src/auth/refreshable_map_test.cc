#include "refreshable_map.h"

#include <gtest/gtest.h>
#include <string>
#include <thread>
#include <vector>
#include <utility>
namespace openmldb::auth {

class RefreshableMapTest : public ::testing::Test {
 protected:
    virtual void SetUp() {}
    virtual void TearDown() {}
};

TEST_F(RefreshableMapTest, GetExistingKey) {
    auto initialMap = std::make_unique<std::unordered_map<std::string, int>>();
    (*initialMap)["key1"] = 100;
    RefreshableMap<std::string, int> map;
    map.Refresh(std::move(initialMap));

    auto value = map.Get("key1");
    ASSERT_TRUE(value.has_value());
    EXPECT_EQ(value.value(), 100);
}

// Test attempting to retrieve a non-existing key
TEST_F(RefreshableMapTest, GetNonExistingKey) {
    auto initialMap = std::make_unique<std::unordered_map<std::string, int>>();
    (*initialMap)["key1"] = 100;
    RefreshableMap<std::string, int> map;
    map.Refresh(std::move(initialMap));

    auto value = map.Get("non_existing_key");
    ASSERT_FALSE(value.has_value());
}

// Test refreshing the map with new data
TEST_F(RefreshableMapTest, RefreshMap) {
    auto initialMap = std::make_unique<std::unordered_map<std::string, int>>();
    (*initialMap)["key1"] = 100;
    RefreshableMap<std::string, int> map;
    map.Refresh(std::move(initialMap));

    auto newMap = std::make_unique<std::unordered_map<std::string, int>>();
    (*newMap)["key2"] = 200;
    map.Refresh(std::move(newMap));

    auto oldKeyValue = map.Get("key1");
    ASSERT_FALSE(oldKeyValue.has_value());

    auto newKeyValue = map.Get("key2");
    ASSERT_TRUE(newKeyValue.has_value());
    EXPECT_EQ(newKeyValue.value(), 200);
}

TEST_F(RefreshableMapTest, ConcurrencySafety) {
    auto initialMap = std::make_unique<std::unordered_map<int, int>>();
    for (int i = 0; i < 100; ++i) {
        (*initialMap)[i] = i;
    }
    RefreshableMap<int, int> map;
    map.Refresh(std::move(initialMap));

    constexpr int numReaders = 10;
    constexpr int numWrites = 5;
    std::vector<std::thread> threads;

    threads.reserve(numReaders);
    for (int i = 0; i < numReaders; ++i) {
        threads.emplace_back([&map]() {
            for (int j = 0; j < 1000; ++j) {  
                auto value = map.Get(rand_r() % 100); 
            }
        });
    }

    // Launch writer thread
    threads.emplace_back([&map]() {
        for (int i = 0; i < numWrites; ++i) {
            auto newMap = std::make_unique<std::unordered_map<int, int>>();
            for (int j = 0; j < 100; ++j) {
                (*newMap)[j] = j + i + 1; 
            }
            map.Refresh(std::move(newMap));
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    });

    for (auto& thread : threads) {
        thread.join();
    }
}
}  // namespace openmldb::auth
