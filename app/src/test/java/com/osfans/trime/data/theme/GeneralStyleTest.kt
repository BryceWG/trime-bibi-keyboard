// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

class GeneralStyleTest :
    BehaviorSpec({
        Given("Correct trime.yaml") {
            val file = File("src/test/assets/trime.yaml")

            When("loaded") {
                val styleNode = Yaml.parseToYamlNode(file.readText()).mapping!!["style"]!!
                val generalStyle = GeneralStyle.decode(styleNode)

                Then("it should not be null") {
                    generalStyle shouldNotBe null
                    generalStyle.autoCaps shouldBe false

                    generalStyle.candidateFont shouldBe listOf("han.ttf")
                }
            }
        }

        Given("Empty trime.yaml") {
            When("loaded") {
                val generalStyle = GeneralStyle.decode(Node.Mapping())

                Then("with default value without exception") {
                    generalStyle.autoCaps shouldBe false
                    generalStyle.candidateBorder shouldBe 0
                    generalStyle.candidateFont shouldBe emptyList()
                    generalStyle.commentPosition shouldBe GeneralStyle.CommentPosition.RIGHT
                    generalStyle.enterLabel shouldNotBe null
                    generalStyle.enterLabel.go shouldBe "go"
                }
            }
        }
    })
