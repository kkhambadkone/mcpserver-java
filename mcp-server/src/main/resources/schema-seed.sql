-- MySQL dump 10.13  Distrib 9.7.0, for macos15 (arm64)
--
-- Host: localhost    Database: customerdb
-- ------------------------------------------------------
-- Server version	9.7.0

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `customers`
--

DROP TABLE IF EXISTS `customers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `customers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `first_name` varchar(100) NOT NULL,
  `last_name` varchar(100) NOT NULL,
  `email` varchar(255) NOT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `address` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_customers_email` (`email`),
  KEY `idx_customers_last_name` (`last_name`),
  KEY `idx_customers_email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `customers`
--

LOCK TABLES `customers` WRITE;
/*!40000 ALTER TABLE `customers` DISABLE KEYS */;
INSERT INTO `customers` VALUES (1,'Alice','Smith','alice@example.com','555-0101','1 Main St, Springfield','2026-05-14 11:32:43.000000','2026-05-14 11:32:43.000000'),(2,'Bob','Jones','bob@example.com','555-0102','2 Oak Ave, Shelbyville','2026-05-14 11:32:43.000000','2026-05-14 11:32:43.000000'),(3,'Carol','Taylor','carol@example.com',NULL,NULL,'2026-05-14 11:32:43.000000','2026-05-14 11:32:43.000000'),(4,'Dave','Brown','dave@example.com','555-0199','5 Elm St, Shelbyville','2026-05-14 22:11:13.316650','2026-05-14 22:11:13.316650'),(5,'JWyatt','Earp','watt.earp@wyatt.org','616-298-2222',NULL,'2026-05-14 22:40:33.788183','2026-05-14 22:40:33.788183'),(6,'Billie','Kid','billie.kid@example.com','213-987-0998','123 East Virginia St., Tombstone, AZ-4099','2026-05-17 18:07:59.395736','2026-05-17 18:07:59.395736'),(7,'Anthony','Bellweather','abellweather@example.com','203-334-9844','123 East Virginia St., Tucson, AZ-40997','2026-05-17 18:31:18.124495','2026-05-17 22:35:24.768632'),(8,'Charlie','Munger','charliemunger@charlie.com','312-908-4844','123 West Cleveland Ave., Cleveland, OH-39909','2026-05-17 19:06:40.646793','2026-05-17 22:36:37.622790'),(9,'Jimmy','Stewart','jstewart@forint.org','213-9987-9998','123 East Virginia St., Tucson, AZ-40997','2026-05-17 22:28:11.604236','2026-05-17 22:28:11.604236'),(10,'Jimmy','Holliday','jholiday@resort.com','334-098-3333','1098 Chesapeake Ave., Milton, OH-40998','2026-05-17 22:38:11.275100','2026-05-17 22:38:11.275100');
/*!40000 ALTER TABLE `customers` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-05-18 11:32:36
