/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package main

import (
	"path"
	"testing"
	"github.com/stretchr/testify/assert"
	"golang.linkedin.com/iddecorator/iddecorator/utils"
)

func TestParseYaml(t *testing.T) {
	expectedMap := utils.ConfigMap {
		map[string]utils.Config {
			"alltypes": utils.Config{
				[]string{
					"/spec/tfReplicaSpecs/Ps/template",
					"/spec/tfReplicaSpecs/Chief/template",
					"/spec/tfReplicaSpecs/Worker/template",
					"/spec/tfReplicaSpecs/Evaluator/template",
				},
				[]string{},
			},
			"tfjobs": utils.Config{
				[]string{
					"/spec/tfReplicaSpecs/Ps/template",
					"/spec/tfReplicaSpecs/Chief/template",
					"/spec/tfReplicaSpecs/Worker/template",
					"/spec/tfReplicaSpecs/Evaluator/template",
				},
				[]string{},
			},
			"pods": utils.Config{
				[]string{"/"},
				[]string{`system:serviceaccount:\S+`},
			},
		},
	}
	yamlFile := path.Join("testdata", "paths.yaml")
	assert.Equal(t, expectedMap, utils.ParseYamlToConfigMap(yamlFile), "they should be equal")
}
