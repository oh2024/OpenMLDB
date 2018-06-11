//
// schema_codec.h
// Copyright (C) 2017 4paradigm.com
// Author vagrant
// Date 2017-09-23
//

#ifndef RTIDB_SCHEMA_CODEC_H
#define RTIDB_SCHEMA_CODEC_H
#include <vector>
#include <map>
#include <string>
#include <cstring>
#include <iostream>
#include "proto/name_server.pb.h"

namespace rtidb {
namespace base {
// 1M
const uint32_t MAX_ROW_BYTE_SIZE = 1024 * 1024;
const uint32_t HEADER_BYTE_SIZE = 3;

enum ColType {
    kString = 0,
    kFloat = 1,
    kInt32 = 2,
    kInt64 = 3,
    kDouble = 4,
    kNull = 5,
    kUInt32 = 6,
    kUInt64 = 7,
    kUnknown = 100
};

struct Column {
    ColType type;
    std::string buffer;
};

struct ColumnDesc {
    ColType type;
    std::string name;
    bool add_ts_idx;
};

class SchemaCodec {

public:
    bool Encode(const std::vector<ColumnDesc>& columns, 
                std::string& buffer) {
        //TODO limit the total size
        uint32_t byte_size = GetSize(columns);     
        if (byte_size >  MAX_ROW_BYTE_SIZE) {
            return false;
        }
        buffer.resize(byte_size);
        char* cbuffer = reinterpret_cast<char*>(&(buffer[0]));
        for (uint32_t i = 0; i < columns.size(); i++) {
            uint8_t type = (uint8_t) columns[i].type;
            memcpy(cbuffer, static_cast<const void*>(&type), 1);
            cbuffer += 1;
            uint8_t add_ts_idx = 0;
            if (columns[i].add_ts_idx) {
                add_ts_idx = 1;
            }
            memcpy(cbuffer, static_cast<const void*>(&add_ts_idx), 1);
            cbuffer += 1;
            //TODO limit the name length
            const std::string& name = columns[i].name;
            if (name.size() >= 128) {
                return false;
            }
            uint8_t name_size = (uint8_t)name.size();
            memcpy(cbuffer, static_cast<const void*>(&name_size), 1);
            cbuffer += 1;
            memcpy(cbuffer, static_cast<const void*>(name.c_str()), name_size);
            cbuffer += name_size;
        }
        return true;
    }

    void Decode(const std::string& schema, std::vector<ColumnDesc>& columns) {
        const char* buffer = schema.c_str();
        uint32_t read_size = 0;
        while (read_size < schema.size()) {
            if (schema.size() - read_size < HEADER_BYTE_SIZE) {
                return;
            }
            uint8_t type = 0;
            memcpy(static_cast<void*>(&type), buffer, 1);
            buffer += 1;
            uint8_t add_ts_idx = 0;
            memcpy(static_cast<void*>(&add_ts_idx), buffer, 1);
            buffer += 1;
            uint8_t name_size = 0;
            memcpy(static_cast<void*>(&name_size), buffer, 1);
            buffer += 1;
            uint32_t total_size = HEADER_BYTE_SIZE + name_size;
            if (schema.size() - read_size < total_size) {
                return;
            }
            std::string name(buffer, name_size);
            buffer += name_size;
            read_size += total_size;
            ColumnDesc desc;
            desc.name = name;
            desc.type = static_cast<ColType>(type);
            desc.add_ts_idx = add_ts_idx;
            columns.push_back(desc);
        }
    }

    static int ConvertColumnDesc(const ::rtidb::nameserver::TableInfo& table_info,
                        std::vector<ColumnDesc>& columns) {
        for (int idx = 0; idx < table_info.column_desc_size(); idx++) {
            ::rtidb::base::ColType type;
            std::string raw_type = table_info.column_desc(idx).type();
            if (raw_type == "int32") {
                type = ::rtidb::base::ColType::kInt32;
            } else if (raw_type == "int64") {
                type = ::rtidb::base::ColType::kInt64;
            } else if (raw_type == "uint32") {
                type = ::rtidb::base::ColType::kUInt32;
            } else if (raw_type == "uint64") {
                type = ::rtidb::base::ColType::kUInt64;
            } else if (raw_type == "float") {
                type = ::rtidb::base::ColType::kFloat;
            } else if (raw_type == "double") {
                type = ::rtidb::base::ColType::kDouble;
            } else if (raw_type == "string") {
                type = ::rtidb::base::ColType::kString;
            } else {
                return -1;
            }
            ColumnDesc column_desc;
            column_desc.type = type;
            column_desc.name = table_info.column_desc(idx).name();
            column_desc.add_ts_idx = table_info.column_desc(idx).add_ts_idx();
            columns.push_back(column_desc);
        }
        return 0;
    }

private:
    // calc the total size of schema
    uint32_t GetSize(const std::vector<ColumnDesc>& columns) {
        uint32_t byte_size = 0;
        for (uint32_t i = 0; i < columns.size(); i++) {
            byte_size += (HEADER_BYTE_SIZE + columns[i].name.size());
        }
        return byte_size;
    }

};

}
}

#endif
