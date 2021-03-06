/**
 * Copyright 2013 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance with
 * the License. A copy of the License is located at
 * 
 * http://aws.amazon.com/apache2.0/
 * 
 * or in the "LICENSE" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package awslabs.lab51;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableResult;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;

/**
 * プロジェクト: Lab5.1
 */
public abstract class SolutionCode implements ILabCode, IOptionalLabCode {
	private Lab51 labController;

	public Lab51 getLabController() {
		return labController;
	}

	public SolutionCode(Lab51 lab) {
		labController = lab;
	}

	@Override
	public String getUrlForItem(AmazonS3Client s3Client, String key, String bucket) {
		Date nowPlusTwoMinutes = new Date(System.currentTimeMillis() + 120000L);

		// 与えられたオブジェクトに対するGeneratePresignedUrlRequestオブジェクトを生成する
		GeneratePresignedUrlRequest generatePresignedUrlRequest = new GeneratePresignedUrlRequest(bucket, key);
		// nowPlusOneHourオブジェクトへのリクエストに有効期限の値を設定する
		// (今から1時間を指定).
		generatePresignedUrlRequest.setExpiration(nowPlusTwoMinutes);

		// s3ClientオブジェクトのgeneratePresignedUrlメソッドを用いてリクエストを送信
		URL url = s3Client.generatePresignedUrl(generatePresignedUrlRequest);
		// URLを文字列として返す
		return url.toString();
	}


	@Override
	public List<Map<String, AttributeValue>> getImageItems(AmazonDynamoDBClient dynamoDbClient) {
		try {
			String tableName = System.getProperty("SESSIONTABLE");
			String keyPrefix = System.getProperty("PARAM3");

			ScanRequest scanRequest = new ScanRequest(tableName).withSelect("ALL_ATTRIBUTES");
			
			if (!keyPrefix.isEmpty()) {
    			Map<String, Condition> scanFilter = new HashMap<String, Condition>();
    			scanFilter.put("Key", new Condition().withAttributeValueList(new AttributeValue().withS(keyPrefix))
    					.withComparisonOperator("BEGINS_WITH"));
    			scanRequest.withScanFilter(scanFilter);
			}

			return dynamoDbClient.scan(scanRequest).getItems();
		} catch (Exception ex) {
			labController.logMessageToPage("getImageItems Error: " + ex.getMessage() + ":" + ex.getStackTrace());
			return null;
		}
	}


	@Override
	public AmazonS3Client createS3Client(AWSCredentials credentials) {
		Region region = Region.getRegion(Regions.fromName(System.getProperty("REGION")));
		AmazonS3Client client = new AmazonS3Client();
		client.setRegion(region);

		return client;
	}


	@Override
	public AmazonDynamoDBClient createDynamoDbClient(AWSCredentials credentials) {
		Region region = Region.getRegion(Regions.fromName(System.getProperty("REGION")));
		AmazonDynamoDBClient client = new AmazonDynamoDBClient();
		client.setRegion(region);

		return client;
	}


	@Override
	public void addItemsToPage(AmazonS3Client s3Client, List<Map<String, AttributeValue>> items) {
		for (Map<String, AttributeValue> item : items) {
			AttributeValue key, bucket;
			if (item.containsKey("Key") && item.containsKey("Bucket")) {
				key = item.get("Key");
				bucket = item.get("Bucket");
				String itemUrl = getUrlForItem(s3Client, key.getS(), bucket.getS());
				labController.addImageToPage(itemUrl, bucket.getS(), key.getS());
			}
		}
	}

	@Override
	public Boolean isImageInDynamo(AmazonDynamoDBClient dynamoDbClient, String tableName, String key) {
		QueryRequest queryRequest = new QueryRequest(tableName).withConsistentRead(true);
		queryRequest.addKeyConditionsEntry("Key",
				new Condition().withComparisonOperator("EQ").withAttributeValueList(new AttributeValue(key)));

		return (dynamoDbClient.query(queryRequest).getCount() > 0);
	}

	@Override
	public Boolean validateSchema(TableDescription tableDescription) {
		if (tableDescription == null) {
			labController.logMessageToPage("Null table description passed to validation method.");
			return false;
		}
		if (!tableDescription.getTableStatus().equals("ACTIVE")) {
			labController.logMessageToPage("Table is not active.");
			return false;
		}

		if (tableDescription.getAttributeDefinitions() == null || tableDescription.getKeySchema() == null) {
			labController.logMessageToPage("Schema doesn't match.");
			return false;
		}
		for (AttributeDefinition attributeDefinition : tableDescription.getAttributeDefinitions()) {
			String attributeName = attributeDefinition.getAttributeName();
			if (attributeName.equals("Key") || attributeName.equals("Bucket")) {
				if (!attributeDefinition.getAttributeType().equals("S")) {
					//マッチする属性があるが、タイプが違う
					labController.logMessageToPage(attributeDefinition.getAttributeName()
							+ " attribute is wrong type in attribute definition.");
					return false;
				}
			}
		}
		// ここに来た場合、属性は正しいのでスキーマをチェックする
		if (tableDescription.getKeySchema().size() != 2) {
			labController.logMessageToPage("Wrong number of elements in the key schema.");
			return false;
		}
		for (KeySchemaElement keySchemaElement : tableDescription.getKeySchema()) {
			String attributeName = keySchemaElement.getAttributeName();
			if (attributeName.equals("Key")) {
				if (!keySchemaElement.getKeyType().equals("HASH")) {
					// マッチする属性があるが、タイプが違う
					labController.logMessageToPage("Key attribute is wrong type in key schema.");
					return false;
				}
			} else if (attributeName.equals("Bucket")) {
				if (!keySchemaElement.getKeyType().equals("RANGE")) {
					// マッチする属性があるが、タイプが違う
					labController.logMessageToPage("Bucket attribute is wrong type in key schema.");
					return false;
				}
			} else {
				labController.logMessageToPage("Unexpected attribute (" + keySchemaElement.getAttributeName()
						+ ") in the key schema.");
			}
		}
		labController.logMessageToPage("Table schema is valid.");
		// チェック合格
		return true;
	}

	@Override
	public TableDescription getTableDescription(AmazonDynamoDBClient ddbClient, String tableName) {
		try {
			DescribeTableRequest describeTableRequest = new DescribeTableRequest().withTableName(tableName);

			DescribeTableResult describeTableResult = ddbClient.describeTable(describeTableRequest);

			return describeTableResult.getTable();
		} catch (AmazonServiceException ase) {
			// テーブルがみつかならない場合は問題なし
			// エラーがその他の場合、例外を再スローして呼び出し元に握りつぶしてもらう
			if (!ase.getErrorCode().equals("ResourceNotFoundException")) {
				throw ase;
			}
			return null;
		}
	}

	@Override
	public String getTableStatus(AmazonDynamoDBClient ddbClient, String tableName) {
		TableDescription tableDescription = getTableDescription(ddbClient, tableName);
		if (tableDescription == null) {
			return "NOTFOUND";
		}
		return tableDescription.getTableStatus();
	}

	@Override
	public void waitForStatus(AmazonDynamoDBClient ddbClient, String tableName, String status) {
		while (!getTableStatus(ddbClient, tableName).equals(status)) {
			// 1秒スリープ
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// 例外を握りつぶす
			}
		}
	}

	
	@Override
	public void deleteTable(AmazonDynamoDBClient ddbClient, String tableName) {
		ddbClient.deleteTable(new DeleteTableRequest().withTableName(tableName));
	}

	@Override
	public void addImage(AmazonDynamoDBClient dynamoDbClient, String tableName, AmazonS3Client s3Client,
			String bucketName, String imageKey, String filePath)  {

		try {
			File file = new File(filePath);
			if (file.exists()) {
				s3Client.putObject(bucketName, imageKey, file);
				
				PutItemRequest putItemRequest = new PutItemRequest().withTableName(tableName);
				putItemRequest.addItemEntry("Key", new AttributeValue(imageKey));
				putItemRequest.addItemEntry("Bucket", new AttributeValue(bucketName));
				dynamoDbClient.putItem(putItemRequest);
				labController.logMessageToPage("Added imageKey: " + imageKey);
			} else {
				labController.logMessageToPage("Image doesn't exist on disk. Skipped: " + imageKey + "[" + filePath + "]");
			}
		} catch (Exception ex) {
			labController.logMessageToPage("addImage Error: " + ex.getMessage());
		}

	}

	@Override
	public void buildTable(AmazonDynamoDBClient ddbClient, String tableName) {
		CreateTableRequest createTableRequest = new CreateTableRequest().withTableName(tableName);
		createTableRequest.setAttributeDefinitions(new ArrayList<AttributeDefinition>());
		// 属性を定義
		createTableRequest.getAttributeDefinitions().add(
				new AttributeDefinition().withAttributeName("Key").withAttributeType("S"));
		createTableRequest.getAttributeDefinitions().add(
				new AttributeDefinition().withAttributeName("Bucket").withAttributeType("S"));
		// キースキーマを定義
		createTableRequest.setKeySchema(new ArrayList<KeySchemaElement>());
		createTableRequest.getKeySchema().add(new KeySchemaElement().withAttributeName("Key").withKeyType("HASH"));
		createTableRequest.getKeySchema().add(new KeySchemaElement().withAttributeName("Bucket").withKeyType("RANGE"));
		// プロビジョンドスループットを定義
		createTableRequest.setProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(5L)
				.withWriteCapacityUnits(5L));

		// リクエストを送信
		ddbClient.createTable(createTableRequest);
		// テーブルがアクティブになるまで停止
		waitForStatus(ddbClient, tableName, "ACTIVE");
		labController.logMessageToPage("Table created and active.");
	}

}
