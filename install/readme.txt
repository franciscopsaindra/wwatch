# Set proxy
export https_proxy=http://127.0.0.1:3128
export http_proxy=http://127.0.0.1:3128

# Maqueta
export https_proxy=http://10.95.85.201:8000
export http_proxy=http://10.95.85.201:8000

# Heroku
set HTTP_PROXY=http://frodriguezg:__@proxy.indra.es:8080
set HTTPS_PROXY=http://frodriguezg:__@proxy.indra.es:8080

# Get java virtual machine
wget --no-check-certificate --header "Cookie: oraclelicense=accept-securebackup-cookie" http://download.oracle.com/otn-pub/java/jdk/8u161-b12/2f38c3b165be4555a1fa6e98c45e0808/jdk-8u161-linux-i586.rpm
rpm -i jdk-8u161-linux-i586.rpm

# Install sbt
curl https://bintray.com/sbt/rpm/rpm | sudo tee /etc/yum.repos.d/bintray-sbt-rpm.repo
sudo yum install sbt



# Install into DigitalOcean

adduser wwatch
passwd wwatch
usermod -aG wheel wwatch

su - wwatch

sudo yum -y install java

sudo yum -y install git

curl https://bintray.com/sbt/rpm/rpm | sudo tee /etc/yum.repos.d/bintray-sbt-rpm.repo
sudo yum -y install sbt

git clone https://github.com/franciscopsaindra/wwatch.git

cd wwatch
sbt
compile
run

# Not needed
sudo firewall-cmd --zone=public --add-port=8800/tcp --permanent
sudo firewall-cmd --zone=public --add-port=8801/tcp --permanent
sudo firewall-cmd --reload