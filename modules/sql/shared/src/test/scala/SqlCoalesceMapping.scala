// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// Copyright (c) 2016-2023 Grackle Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package grackle.sql.test

import grackle.syntax._

trait SqlCoalesceMapping[F[_]] extends SqlTestMapping[F] {

  object r extends TableDef("r") {
    val id = col("id", text)
  }

  object ca extends TableDef("ca") {
    val id = col("id", text)
    val rid = col("rid", text)
    val a = col("a", int4)
  }

  object cb extends TableDef("cb") {
    val id = col("id", text)
    val rid = col("rid", text)
    val b = col("b", bool)
  }

  val schema =
    schema"""
      type Query {
        r: [R!]!
      }
      type R {
        id: String!
        ca: [CA!]!
        cb: [CB!]!
      }
      type CA {
        id: String!
        a: Int!
      }
      type CB {
        id: String!
        b: Boolean!
      }
    """

  val QueryType = schema.ref("Query")
  val RType = schema.ref("R")
  val CAType = schema.ref("CA")
  val CBType = schema.ref("CB")

  val typeMappings =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings =
          List(
            SqlObject("r")
          )
      ),
      ObjectMapping(
        tpe = RType,
        fieldMappings =
          List(
            SqlField("id", r.id, key = true),
            SqlObject("ca", Join(r.id, ca.rid)),
            SqlObject("cb", Join(r.id, cb.rid)),
          )
      ),
      ObjectMapping(
        tpe = CAType,
        fieldMappings =
          List(
            SqlField("id", ca.id, key = true),
            SqlField("rid", ca.rid, hidden = true),
            SqlField("a", ca.a)
          )
      ),
      ObjectMapping(
        tpe = CBType,
        fieldMappings =
          List(
            SqlField("id", cb.id, key = true),
            SqlField("rid", cb.rid, hidden = true),
            SqlField("b", cb.b)
          )
      )
    )
}
